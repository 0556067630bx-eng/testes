package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.Employee
import com.example.myapplication.ui.components.*
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.DayData
import com.google.gson.Gson
import android.content.Context
import android.net.Uri
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri: Uri? ->
        uri?.let { importDataFromUri(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        uri: Uri? ->
        uri?.let { exportDataToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = AppDatabase.getDatabase(applicationContext)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                    ) {
                        TimeControlScreen(database, this::importData, this::exportData, applicationContext)
                    }
                }
            }
        }
    }

    private fun importData() {
        importLauncher.launch(arrayOf("application/json"))
    }

    private fun exportData() {
        val fileName = "controle-horas-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.json"
        exportLauncher.launch(fileName)
    }

    private fun importDataFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = applicationContext.contentResolver
                val inputStream = contentResolver.openInputStream(uri)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val jsonString = reader.use { it.readText() }
                inputStream?.close()

                val importObject = Gson().fromJson(jsonString, ImportExportData::class.java)

                if (importObject?.data == null || importObject.data.isEmpty()) {
                    withContext(Dispatchers.Main) { showToast("Arquivo de backup inválido ou vazio!") }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    // Implementar confirmação do usuário aqui se necessário
                    // Por simplicidade, vamos importar diretamente
                    lifecycleScope.launch(Dispatchers.IO) {
                        database.dayDataDao().clearAllData()
                        importObject.data.forEach { dayData ->
                            database.dayDataDao().insertDayData(dayData)
                        }
                        withContext(Dispatchers.Main) { showToast("Dados importados com sucesso!") }
                        // Forçar recarregamento da tela se necessário
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { showToast("Erro ao importar dados: ${e.message}") }
            }
        }
    }

    private fun exportDataToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allData = database.dayDataDao().getAllDayData()
                if (allData.isEmpty()) {
                    withContext(Dispatchers.Main) { showToast("Não há dados para exportar!") }
                    return@launch
                }

                val exportObject = ImportExportData(
                    exportDate = Date().toInstant().toString(),
                    version = "1.0",
                    appName = "ControleHoras",
                    data = allData
                )

                val jsonString = Gson().toJson(exportObject)

                val contentResolver = applicationContext.contentResolver
                val outputStream = contentResolver.openOutputStream(uri)
                val writer = OutputStreamWriter(outputStream)
                writer.use { it.write(jsonString) }
                outputStream?.close()

                withContext(Dispatchers.Main) { showToast("Dados exportados com sucesso!") }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { showToast("Erro ao exportar dados: ${e.message}") }
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

data class ImportExportData(
    val exportDate: String,
    val version: String,
    val appName: String,
    val data: List<DayData>
)

@Composable
fun TimeControlScreen(database: AppDatabase, importData: () -> Unit, exportData: () -> Unit, context: Context) {
    val dayDataDao = database.dayDataDao()
    val coroutineScope = rememberCoroutineScope()

    var currentDate by remember { mutableStateOf(Calendar.getInstance()) }
    var currentWeek by remember { mutableStateOf(getWeekNumber(currentDate.time)) }
    var employees by remember { mutableStateOf(mutableStateListOf<Employee>()) }
    var dailyNotes by remember { mutableStateOf("") }

    // Função para salvar dados no banco de dados
    fun saveCurrentDayData() {
        coroutineScope.launch(Dispatchers.IO) {
            val dateKey = formatDateKey(currentDate.time)
            val dayData = DayData(date = dateKey, employees = employees.toList(), dailyNotes = dailyNotes)
            dayDataDao.insertDayData(dayData)
            withContext(Dispatchers.Main) { Toast.makeText(context, "Dados salvos!", Toast.LENGTH_SHORT).show() }
        }
    }

    // Efeito para carregar dados do dia atual do banco de dados
    LaunchedEffect(currentDate.time) {
        val dateKey = formatDateKey(currentDate.time)
        dayDataDao.getDayData(dateKey).collect { dayData ->
            if (dayData != null) {
                employees.clear()
                employees.addAll(dayData.employees.map { emp ->
                    emp.copy(totalHours = calculateTotalHours(emp.timeIn, emp.timeOut))
                })
                dailyNotes = dayData.dailyNotes
            } else {
                employees.clear()
                dailyNotes = ""
            }
        }
    }

    // Funções de manipulação de data e semana
    fun updateWeekAndDate(newDate: Calendar) {
        currentDate = newDate
        currentWeek = getWeekNumber(newDate.time)
    }

    fun goToPreviousWeek() {
        val newDate = currentDate.clone() as Calendar
        newDate.add(Calendar.WEEK_OF_YEAR, -1)
        updateWeekAndDate(newDate)
    }

    fun goToNextWeek() {
        val newDate = currentDate.clone() as Calendar
        newDate.add(Calendar.WEEK_OF_YEAR, 1)
        updateWeekAndDate(newDate)
    }

    fun goToPreviousDay() {
        val newDate = currentDate.clone() as Calendar
        newDate.add(Calendar.DAY_OF_YEAR, -1)
        updateWeekAndDate(newDate)
    }

    fun goToNextDay() {
        val newDate = currentDate.clone() as Calendar
        newDate.add(Calendar.DAY_OF_YEAR, 1)
        updateWeekAndDate(newDate)
    }

    // Funções de manipulação de empregados
    fun addEmployee() {
        employees.add(Employee())
        saveCurrentDayData()
    }

    fun removeEmployee(employee: Employee) {
        employees.remove(employee)
        saveCurrentDayData()
    }

    fun updateEmployee(updatedEmployee: Employee) {
        val index = employees.indexOfFirst { it.id == updatedEmployee.id }
        if (index != -1) {
            employees[index] = updatedEmployee.copy(
                totalHours = calculateTotalHours(updatedEmployee.timeIn, updatedEmployee.timeOut)
            )
            saveCurrentDayData()
        }
    }

    // Funções de cópia
    fun copyToTomorrow() {
        coroutineScope.launch(Dispatchers.IO) {
            val tomorrow = currentDate.clone() as Calendar
            tomorrow.add(Calendar.DAY_OF_YEAR, 1)
            val employeesForTomorrow = employees.map { it.copy(timeIn = "", timeOut = "", totalHours = "") }
            val dayData = DayData(formatDateKey(tomorrow.time), employeesForTomorrow, "")
            dayDataDao.insertDayData(dayData)
            withContext(Dispatchers.Main) { Toast.makeText(context, "Trabalhadores copiados para o dia seguinte!", Toast.LENGTH_SHORT).show() }
        }
    }

    fun copyToWeek() {
        coroutineScope.launch(Dispatchers.IO) {
            val currentDayOfWeek = currentDate.get(Calendar.DAY_OF_WEEK)
            val mondayOffset = if (currentDayOfWeek == Calendar.SUNDAY) -6 else Calendar.MONDAY - currentDayOfWeek

            val monday = currentDate.clone() as Calendar
            monday.add(Calendar.DAY_OF_YEAR, mondayOffset)

            for (i in 0 until 5) { // Segunda a Sexta
                val targetDate = monday.clone() as Calendar
                targetDate.add(Calendar.DAY_OF_YEAR, i)
                if (formatDateKey(targetDate.time) != formatDateKey(currentDate.time)) {
                    val employeesForDay = employees.map { it.copy(timeIn = "", timeOut = "", totalHours = "") }
                    val dayData = DayData(formatDateKey(targetDate.time), employeesForDay, "")
                    dayDataDao.insertDayData(dayData)
                }
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, "Trabalhadores copiados para todos os dias da semana (segunda a sexta)!", Toast.LENGTH_SHORT).show() }
        }
    }

    fun copyFromYesterday() {
        coroutineScope.launch(Dispatchers.IO) {
            val yesterday = currentDate.clone() as Calendar
            yesterday.add(Calendar.DAY_OF_YEAR, -1)
            val yesterdayKey = formatDateKey(yesterday.time)

            val yesterdayData = dayDataDao.getDayData(yesterdayKey).firstOrNull()
            if (yesterdayData != null && yesterdayData.employees.isNotEmpty()) {
                employees.clear()
                employees.addAll(yesterdayData.employees.map { it.copy(timeIn = "", timeOut = "", totalHours = "") })
                dailyNotes = ""
                saveCurrentDayData()
                withContext(Dispatchers.Main) { Toast.makeText(context, "Trabalhadores copiados do dia anterior!", Toast.LENGTH_SHORT).show() }
            } else {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Não há dados registrados para o dia anterior!", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun copyFromMonday() {
        coroutineScope.launch(Dispatchers.IO) {
            val currentDayOfWeek = currentDate.get(Calendar.DAY_OF_WEEK)
            if (currentDayOfWeek == Calendar.MONDAY) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Você já está na segunda-feira!", Toast.LENGTH_SHORT).show() }
                return@launch
            }

            val mondayOffset = if (currentDayOfWeek == Calendar.SUNDAY) -6 else Calendar.MONDAY - currentDayOfWeek
            val monday = currentDate.clone() as Calendar
            monday.add(Calendar.DAY_OF_YEAR, mondayOffset)
            val mondayKey = formatDateKey(monday.time)

            val mondayData = dayDataDao.getDayData(mondayKey).firstOrNull()
            if (mondayData != null && mondayData.employees.isNotEmpty()) {
                employees.clear()
                employees.addAll(mondayData.employees.map { it.copy(timeIn = "", timeOut = "", totalHours = "") })
                dailyNotes = ""
                saveCurrentDayData()
                withContext(Dispatchers.Main) { Toast.makeText(context, "Trabalhadores copiados da segunda-feira!", Toast.LENGTH_SHORT).show() }
            } else {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Não há dados registrados para segunda-feira desta semana!", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        WeekNavigation(
            currentWeek = currentWeek.toString(),
            onPreviousWeekClick = { goToPreviousWeek() },
            onNextWeekClick = { goToNextWeek() }
        )
        Spacer(modifier = Modifier.height(8.dp))
        DateNavigation(
            currentDate = formatDate(currentDate.time),
            onPreviousDayClick = { goToPreviousDay() },
            onNextDayClick = { goToNextDay() }
        )
        Spacer(modifier = Modifier.height(8.dp))
        DropdownMenu(
            onCopyToTomorrow = { copyToTomorrow() },
            onCopyToWeek = { copyToWeek() },
            onCopyFromYesterday = { copyFromYesterday() },
            onCopyFromMonday = { copyFromMonday() }
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Trabalhador", modifier = Modifier.weight(1f))
            Text("Entrada", modifier = Modifier.weight(0.5f))
            Text("Saída", modifier = Modifier.weight(0.5f))
            Text("Total Horas", modifier = Modifier.weight(0.5f))
            Spacer(modifier = Modifier.width(48.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))

        employees.forEach { employee ->
            EmployeeRow(
                employee = employee,
                onNameChange = { newName -> updateEmployee(employee.copy(name = newName)) },
                onTimeInChange = { newTimeIn -> updateEmployee(employee.copy(timeIn = newTimeIn)) },
                onTimeOutChange = { newTimeOut -> updateEmployee(employee.copy(timeOut = newTimeOut)) },
                onRemove = { removeEmployee(employee) }
            )
        }

        AddEmployeeButton(onAddEmployee = { addEmployee() })
        Spacer(modifier = Modifier.height(16.dp))

        ActionButtons(
            onImportData = importData,
            onExportData = exportData,
            onSaveData = { saveCurrentDayData() }
        )
        Spacer(modifier = Modifier.height(16.dp))

        DailyNotesInput(
            notes = dailyNotes,
            onNotesChange = { newNotes ->
                dailyNotes = newNotes
                saveCurrentDayData()
            }
        )
    }
}

// Helper functions
fun getWeekNumber(date: Date): Int {
    val calendar = Calendar.getInstance()
    calendar.time = date
    return calendar.get(Calendar.WEEK_OF_YEAR)
}

fun formatDate(date: Date): String {
    val sdf = SimpleDateFormat("EEEE dd/MM/yyyy", Locale("pt", "BR"))
    return sdf.format(date)
}

fun formatDateKey(date: Date): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(date)
}

fun calculateTotalHours(timeIn: String, timeOut: String): String {
    if (timeIn.isBlank() || timeOut.isBlank()) return ""

    val (inHour, inMin) = timeIn.split(":").map { it.toInt() }
    val (outHour, outMin) = timeOut.split(":").map { it.toInt() }

    var totalMinutes = (outHour * 60 + outMin) - (inHour * 60 + inMin)
    if (totalMinutes < 0) totalMinutes += 24 * 60 // Handle overnight shifts

    // Desconta 1 hora para almoço se trabalhou mais de 6 horas (360 minutos)
    if (totalMinutes > 360) totalMinutes -= 60

    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return String.format("%d:%02d", hours, minutes)
}

@Preview(showBackground = true)
@Composable
fun TimeControlScreenPreview() {
    MyApplicationTheme {
        Text("Preview not fully functional without database instance")
    }
}

