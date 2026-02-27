package com.dicoding.myworkmanager

import com.squareup.moshi.Json

/**
 * # Response
 *
 * **Model data utama** yang merepresentasikan struktur JSON response dari API OpenWeatherMap
 * endpoint `/data/2.5/weather`.
 *
 * ## Mengapa Data Class?
 * Kotlin `data class` secara otomatis meng-generate `equals()`, `hashCode()`, `toString()`,
 * dan `copy()` — ideal untuk model data yang hanya menyimpan nilai (Value Object).
 *
 * ## Mengapa Moshi + `@Json`?
 * Moshi digunakan untuk mem-parse JSON response secara **type-safe** ke objek Kotlin.
 * Anotasi `@Json(name = "...")` digunakan ketika nama field di JSON berbeda dengan
 * nama properti Kotlin (mengikuti konvensi camelCase Kotlin vs snake_case JSON).
 *
 * ## Hubungan dengan File Lain:
 * - **[MyWorker]**: Yang menggunakan `Moshi` untuk mengubah JSON response API menjadi objek ini.
 * - **[Weather]** & **[Main]**: Sub-model yang menjadi properti dari kelas ini.
 *
 * @property id ID unik kota dari OpenWeatherMap (bisa digunakan untuk query lebih efisien).
 * @property name Nama kota yang dikembalikan oleh API (bisa berbeda kapitalisasi dengan input).
 * @property weatherList Daftar kondisi cuaca saat ini. API mengembalikan array `weather`,
 *           namun dalam praktiknya biasanya hanya berisi **satu elemen**.
 * @property main Objek berisi data meteorologi utama (suhu, kelembapan, tekanan, dll).
 */
data class Response(
    val id: Int,
    val name: String,
    // `@Json(name = "weather")` memetakan field JSON bernama "weather" ke properti Kotlin
    // bernama `weatherList`. Nama berbeda dipakai agar lebih deskriptif (menunjukkan ini adalah list).
    @Json(name = "weather")
    val weatherList: List<Weather>,
    val main: Main,
)

/**
 * # Weather
 *
 * Merepresentasikan **satu kondisi cuaca** dari array `weather` dalam response API.
 *
 * ## Catatan:
 * API OpenWeatherMap bisa mengembalikan lebih dari satu kondisi cuaca secara bersamaan
 * (misalnya: hujan + berawan), namun biasanya hanya elemen pertama (`weatherList[0]`)
 * yang digunakan untuk ditampilkan kepada pengguna.
 *
 * @property main Kategori cuaca utama (singkat), contoh: `"Rain"`, `"Clear"`, `"Clouds"`.
 * @property description Deskripsi cuaca yang lebih rinci, contoh: `"light rain"`, `"broken clouds"`.
 */
data class Weather(
    val main: String,
    val description: String
)

/**
 * # Main
 *
 * Berisi **data meteorologi utama** dari response API, termasuk suhu, tekanan, dan kelembapan.
 * Di sini hanya diambil properti `temp` yang diperlukan oleh aplikasi.
 *
 * ## Catatan Satuan Suhu:
 * Secara default, API OpenWeatherMap mengembalikan suhu dalam **Kelvin**.
 * Konversi ke Celsius dilakukan di **[MyWorker]** dengan rumus: `Celsius = Kelvin - 273`.
 * Untuk mendapatkan Celsius langsung dari API, tambahkan parameter `units=metric` di URL.
 *
 * @property temperature Suhu saat ini dalam **Kelvin**. Di-mapping dari field JSON `"temp"`.
 */
data class Main(
    // `@Json(name = "temp")` memetakan field JSON "temp" ke nama properti yang lebih deskriptif.
    @Json(name = "temp")
    val temperature: Double
)