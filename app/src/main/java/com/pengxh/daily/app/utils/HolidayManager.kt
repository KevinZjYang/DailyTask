package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 节假日管理器
 * 支持联网获取节假日数据和本地缓存
 */
object HolidayManager {

    private const val HOLIDAY_ENABLED_KEY = "HOLIDAY_ENABLED_KEY"
    private const val WORK_ON_WEEKEND_KEY = "WORK_ON_WEEKEND_KEY"
    private const val HOLIDAY_CACHE_KEY = "HOLIDAY_CACHE_KEY"
    private const val HOLIDAY_CACHE_DATE_KEY = "HOLIDAY_CACHE_DATE_KEY"

    private const val HOLIDAY_API_URL = "https://www.shuyz.com/githubfiles/china-holiday-calender/master/holidayAPI.json"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val yearFormat = SimpleDateFormat("yyyy", Locale.CHINA)

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // 内存缓存：日期 -> 节假日状态
    private var holidayCache: MutableMap<String, HolidayStatus> = mutableMapOf()

    /**
     * 是否启用节假日跳过功能
     */
    fun isHolidayEnabled(): Boolean {
        return SaveKeyValues.getValue(HOLIDAY_ENABLED_KEY, true) as Boolean
    }

    fun setHolidayEnabled(enabled: Boolean) {
        SaveKeyValues.putValue(HOLIDAY_ENABLED_KEY, enabled)
    }

    /**
     * 周末是否需要补班（调休模式）
     */
    fun isWorkOnWeekend(): Boolean {
        return SaveKeyValues.getValue(WORK_ON_WEEKEND_KEY, false) as Boolean
    }

    fun setWorkOnWeekend(work: Boolean) {
        SaveKeyValues.putValue(WORK_ON_WEEKEND_KEY, work)
    }

    /**
     * 初始化：从网络获取节假日数据（异步）
     * 应用启动时调用
     */
    fun init() {
        CoroutineScope(Dispatchers.IO).launch {
            fetchHolidayData()
        }
    }

    /**
     * 从网络获取节假日数据
     */
    private fun fetchHolidayData() {
        try {
            val request = Request.Builder()
                .url(HOLIDAY_API_URL)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val jsonString = response.body?.string() ?: return

            // 解析并缓存
            parseAndCacheHolidayData(jsonString)
            LogFileManager.writeLog("节假日数据更新成功")
        } catch (e: Exception) {
            LogFileManager.writeLog("获取节假日数据失败: ${e.message}")
            // 加载本地内置数据作为兜底
            loadBuiltInDataToCache()
        }
    }

    /**
     * 解析节假日JSON数据并缓存
     */
    private fun parseAndCacheHolidayData(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val years = json.optJSONObject("Years") ?: return

            holidayCache.clear()

            for (yearKey in years.keys()) {
                val yearArray = years.optJSONArray(yearKey) ?: continue
                for (i in 0 until yearArray.length()) {
                    val holiday = yearArray.getJSONObject(i)
                    val name = holiday.optString("Name", "")
                    val startDate = holiday.optString("StartDate", "")
                    val endDate = holiday.optString("EndDate", "")
                    val compDaysArray = holiday.optJSONArray("CompDays")

                    // 解析调休工作日
                    val compDays = mutableListOf<String>()
                    compDaysArray?.let {
                        for (j in 0 until it.length()) {
                            it.getString(j)?.let { day -> compDays.add(day) }
                        }
                    }

                    // 填充节假日范围内的日期
                    if (startDate.isNotEmpty() && endDate.isNotEmpty()) {
                        var currentDate = startDate
                        while (currentDate <= endDate) {
                            holidayCache[currentDate] = HolidayStatus.HOLIDAY
                            currentDate = getNextDate(currentDate)
                        }
                    }

                    // 标记调休工作日
                    for (compDay in compDays) {
                        holidayCache[compDay] = HolidayStatus.WORKDAY
                    }
                }
            }

            // 缓存到本地
            saveCacheToLocal(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            loadBuiltInDataToCache()
        }
    }

    /**
     * 将网络数据缓存到本地
     */
    private fun saveCacheToLocal(jsonString: String) {
        SaveKeyValues.putValue(HOLIDAY_CACHE_KEY, jsonString)
        SaveKeyValues.putValue(HOLIDAY_CACHE_DATE_KEY, dateFormat.format(System.currentTimeMillis()))
    }

    /**
     * 从本地缓存加载节假日数据
     */
    private fun loadCacheFromLocal() {
        val cachedJson = SaveKeyValues.getValue(HOLIDAY_CACHE_KEY, "") as String
        if (cachedJson.isNotEmpty()) {
            parseAndCacheHolidayData(cachedJson)
        } else {
            // 没有缓存，加载内置数据
            loadBuiltInDataToCache()
        }
    }

    /**
     * 加载内置数据到缓存（兜底方案）
     */
    private fun loadBuiltInDataToCache() {
        val today = dateFormat.format(System.currentTimeMillis())
        val year = today.substring(0, 4)
        val builtInHolidays = getBuiltInHolidays(year)

        holidayCache.clear()
        for ((date, status) in builtInHolidays) {
            holidayCache[date] = HolidayStatus.fromInt(status)
        }
    }

    /**
     * 获取指定日期的后一天
     */
    private fun getNextDate(date: String): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = Calendar.getInstance()
        cal.time = sdf.parse(date) ?: return date
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return sdf.format(cal.time)
    }

    /**
     * 判断今天是否需要执行任务
     * @return true = 需要执行任务，false = 跳过任务
     */
    fun shouldExecuteTask(): Boolean {
        if (!isHolidayEnabled()) {
            return true
        }

        // 如果缓存为空，先加载
        if (holidayCache.isEmpty()) {
            loadCacheFromLocal()
        }

        val today = dateFormat.format(System.currentTimeMillis())
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        // 先从缓存检查是否为节假日
        val cachedStatus = holidayCache[today]
        if (cachedStatus != null) {
            when (cachedStatus) {
                HolidayStatus.HOLIDAY -> {
                    LogFileManager.writeLog("今天是节假日，跳过任务执行")
                    return false
                }
                HolidayStatus.WORKDAY -> {
                    LogFileManager.writeLog("今天是调休工作日，正常执行任务")
                    return true
                }
                HolidayStatus.NORMAL_WORKDAY -> {
                    return true
                }
            }
        }

        // 周末判断（当缓存没有数据时）
        if (isWorkOnWeekend() && (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)) {
            LogFileManager.writeLog("周末补班模式，今天需要执行任务")
            return true
        }

        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            LogFileManager.writeLog("今天是周末，跳过任务执行")
            return false
        }

        return true
    }

    /**
     * 内置节假日数据（兜底方案）
     * 0 = 普通工作日, 1 = 节假日, 2 = 调休工作日
     */
    private fun getBuiltInHolidays(year: String): Map<String, Int> {
        val holidays = mutableMapOf<String, Int>()

        when (year) {
            "2026" -> {
                // 元旦：1月1日-3日放假，1月4日（周日）上班
                holidays["2026-01-01"] = 1
                holidays["2026-01-02"] = 1
                holidays["2026-01-03"] = 1
                holidays["2026-01-04"] = 2  // 调休工作日

                // 春节：2月15日-23日放假，2月14日（周六）、2月28日（周六）上班
                holidays["2026-02-14"] = 2  // 调休工作日
                holidays["2026-02-15"] = 1
                holidays["2026-02-16"] = 1
                holidays["2026-02-17"] = 1
                holidays["2026-02-18"] = 1
                holidays["2026-02-19"] = 1
                holidays["2026-02-20"] = 1
                holidays["2026-02-21"] = 1
                holidays["2026-02-22"] = 1
                holidays["2026-02-23"] = 1
                holidays["2026-02-28"] = 2  // 调休工作日

                // 清明节：4月4日-6日放假
                holidays["2026-04-04"] = 1
                holidays["2026-04-05"] = 1
                holidays["2026-04-06"] = 1

                // 劳动节：5月1日-5日放假，5月9日（周六）上班
                holidays["2026-05-01"] = 1
                holidays["2026-05-02"] = 1
                holidays["2026-05-03"] = 1
                holidays["2026-05-04"] = 1
                holidays["2026-05-05"] = 1
                holidays["2026-05-09"] = 2  // 调休工作日

                // 端午节：6月19日-21日放假
                holidays["2026-06-19"] = 1
                holidays["2026-06-20"] = 1
                holidays["2026-06-21"] = 1

                // 中秋节：9月25日-27日放假
                holidays["2026-09-25"] = 1
                holidays["2026-09-26"] = 1
                holidays["2026-09-27"] = 1

                // 国庆节：10月1日-7日放假，9月20日（周日）、10月10日（周六）上班
                holidays["2026-09-20"] = 2  // 调休工作日
                holidays["2026-10-01"] = 1
                holidays["2026-10-02"] = 1
                holidays["2026-10-03"] = 1
                holidays["2026-10-04"] = 1
                holidays["2026-10-05"] = 1
                holidays["2026-10-06"] = 1
                holidays["2026-10-07"] = 1
                holidays["2026-10-10"] = 2  // 调休工作日
            }
            "2027" -> {
                // 元旦
                holidays["2027-01-01"] = 1
                holidays["2027-01-02"] = 1
                holidays["2027-01-03"] = 1

                // 春节（假设）
                holidays["2027-02-16"] = 1
                holidays["2027-02-17"] = 1
                holidays["2027-02-18"] = 1
                holidays["2027-02-19"] = 1
                holidays["2027-02-20"] = 1
                holidays["2027-02-21"] = 1
                holidays["2027-02-22"] = 1

                // 清明节
                holidays["2027-04-05"] = 1
                holidays["2027-04-06"] = 1
                holidays["2027-04-07"] = 1

                // 劳动节
                holidays["2027-05-01"] = 1
                holidays["2027-05-02"] = 1
                holidays["2027-05-03"] = 1

                // 端午节
                holidays["2027-06-20"] = 1
                holidays["2027-06-21"] = 1
                holidays["2027-06-22"] = 1

                // 中秋节+国庆节
                holidays["2027-10-01"] = 1
                holidays["2027-10-02"] = 1
                holidays["2027-10-03"] = 1
                holidays["2027-10-04"] = 1
                holidays["2027-10-05"] = 1
                holidays["2027-10-06"] = 1
                holidays["2027-10-07"] = 1
                holidays["2027-10-08"] = 1
            }
        }

        return holidays
    }

    enum class HolidayStatus {
        NORMAL_WORKDAY,    // 普通工作日
        HOLIDAY,           // 节假日
        WORKDAY;           // 调休工作日

        companion object {
            fun fromInt(value: Int): HolidayStatus {
                return when (value) {
                    1 -> HOLIDAY
                    2 -> WORKDAY
                    else -> NORMAL_WORKDAY
                }
            }
        }
    }
}