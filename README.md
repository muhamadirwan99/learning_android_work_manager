# 🗓️ MyWorkManager

> Aplikasi Android demo yang menunjukkan cara menjadwalkan dan mengelola **pekerjaan latar belakang yang terjamin** menggunakan **Jetpack WorkManager** — solusi resmi yang direkomendasikan Google untuk background task.

---

## 📱 Screenshots

| Tampilan Utama | Notifikasi Cuaca | Status Task |
|:-:|:-:|:-:|
| ![Screenshot Home](screenshots/home.png) | ![Notification](screenshots/notification.png) | ![Status](screenshots/status.png) |

> 📌 *Ganti placeholder di atas dengan screenshot atau GIF aplikasi Anda.*

---

## ✨ Fitur Utama

| Fitur | Deskripsi |
|---|---|
| 🔁 **Periodic Task** | Menjadwalkan tugas berulang setiap 15 menit secara otomatis |
| ▶️ **One-Time Task** | Menjalankan tugas sekali berdasarkan aksi pengguna |
| ❌ **Cancel Task** | Membatalkan periodic task yang sedang aktif berdasarkan UUID unik |
| 🌤️ **Fetch Data Cuaca** | Mengambil data cuaca real-time dari API OpenWeatherMap |
| 🔔 **Push Notification** | Menampilkan hasil cuaca sebagai notifikasi sistem |
| 🌐 **Network Constraint** | Task otomatis ditahan jika tidak ada koneksi internet |
| 📊 **Live Status Monitor** | Memantau status task (ENQUEUED → RUNNING → SUCCEEDED) secara real-time |

---

## 🛠️ Teknologi yang Digunakan

| Teknologi | Kegunaan |
|---|---|
| **Kotlin** | Bahasa pemrograman utama |
| **Jetpack WorkManager** | Penjadwalan & eksekusi background task yang terjamin |
| **ViewBinding** | Akses komponen UI secara type-safe tanpa `findViewById` |
| **Moshi** | Parsing JSON response API ke data class Kotlin |
| **android-async-http (SyncHttpClient)** | HTTP request sinkronus di background thread |
| **NotificationCompat** | Menampilkan notifikasi yang kompatibel lintas versi Android |
| **LiveData** | Observasi status task secara reaktif, lifecycle-aware |
| **ActivityResultContracts** | Meminta izin runtime (POST_NOTIFICATIONS) dengan API modern |

---

## 🎓 Pelajaran Penting (Key Takeaways)

### 1. 🔄 `Worker` vs `CoroutineWorker`
`Worker` adalah implementasi **sinkronus** — `doWork()` memblokir thread hingga selesai. Ini aman karena WorkManager sudah menjalankannya di background thread secara otomatis. Gunakan `CoroutineWorker` hanya jika Anda ingin memanfaatkan Kotlin `suspend functions` dan coroutine secara langsung.

```
Worker          → Sinkronus, cocok untuk SyncHttpClient
CoroutineWorker → Asinkronus, cocok untuk Retrofit + suspend fun
```

### 2. ⏱️ Batas Minimum 15 Menit di `PeriodicWorkRequest`
Android **memaksakan** interval minimum 15 menit untuk `PeriodicWorkRequest` demi menjaga efisiensi baterai. Nilai yang lebih kecil dari ini akan **secara otomatis dinaikkan** ke 15 menit oleh sistem. Di mode Doze/Battery Optimization, task bisa berjalan lebih lambat dari jadwal.

```kotlin
// ✅ Minimum mutlak — tidak bisa lebih kecil dari ini
PeriodicWorkRequest.Builder(MyWorker::class.java, 15, TimeUnit.MINUTES)
```

### 3. 🆚 `SyncHttpClient` vs `AsyncHttpClient` di Worker
Menggunakan `AsyncHttpClient` (asinkronus) di dalam `doWork()` itu **berbahaya** — `doWork()` bisa selesai *sebelum* callback HTTP dipanggil, sehingga WorkManager menganggap task sudah selesai padahal data belum diterima. `SyncHttpClient` memblokir thread hingga response tiba, memastikan result yang benar dikembalikan.

```
AsyncHttpClient → ❌ doWork() selesai lebih dulu → Result salah
SyncHttpClient  → ✅ Memblokir hingga response ada → Result akurat
```

### 4. 🔑 Keamanan API Key dengan `BuildConfig`
API Key **tidak boleh** di-hardcode langsung di kode sumber. Project ini membaca API Key dari `local.properties` (yang **tidak di-commit** ke Version Control), lalu meng-injectnya ke `BuildConfig` melalui `buildConfigField` di Gradle saat proses build.

```
local.properties (rahasia) → build.gradle.kts (buildConfigField) → BuildConfig.API_KEY
```

### 5. 📡 Constraints: Jaringan sebagai Gatekeeper
`Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)` memastikan task **tidak pernah gagal karena tidak ada jaringan** — WorkManager akan menahan task di antrian (`ENQUEUED`) hingga koneksi tersedia, bukan langsung menandainya `FAILED`.

---

## ⚙️ Cara Setup

### Prasyarat
- Android Studio Hedgehog atau lebih baru
- Android SDK minimum API 24 (Android 7.0)
- Koneksi internet
- API Key dari [OpenWeatherMap](https://openweathermap.org/api) (gratis)

### Langkah-langkah

**1. Clone repository**
```bash
git clone https://github.com/username/MyWorkManager.git
cd MyWorkManager
```

**2. Tambahkan API Key**

Buka atau buat file `local.properties` di **root project**, lalu tambahkan:
```properties
API_KEY=masukkan_api_key_openweathermap_anda_di_sini
```

> ⚠️ **Jangan commit `local.properties` ke Git!** File ini sudah seharusnya ada di `.gitignore`.

**3. Build & Run**

Buka project di Android Studio, tunggu Gradle sync selesai, lalu jalankan di emulator atau perangkat fisik.

---

## 🏗️ Struktur Project

```
MyWorkManager/
├── app/src/main/
│   ├── java/com/dicoding/myworkmanager/
│   │   ├── MainActivity.kt      # UI utama & kontrol WorkManager
│   │   ├── MyWorker.kt          # Logika background task (fetch cuaca + notifikasi)
│   │   └── Response.kt          # Data model untuk parsing JSON (Moshi)
│   ├── res/layout/
│   │   └── activity_main.xml    # Layout UI dengan input kota & tombol kontrol
│   └── AndroidManifest.xml      # Izin INTERNET & POST_NOTIFICATIONS
├── gradle/
│   └── libs.versions.toml       # Version catalog untuk dependency management
├── app/build.gradle.kts         # Konfigurasi build & injection API Key
└── local.properties             # API Key (JANGAN di-commit!)
```

---

## 📦 Dependencies Utama

```toml
# gradle/libs.versions.toml

[versions]
workRuntime        = "2.11.1"
moshiKotlin        = "1.11.0"
androidAsyncHttp   = "1.4.11"

[libraries]
androidx-work-runtime  = { module = "androidx.work:work-runtime" }
moshi-kotlin           = { module = "com.squareup.moshi:moshi-kotlin" }
android-async-http     = { module = "com.loopj.android:android-async-http" }
```

---

## 📋 Izin yang Diperlukan

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

> 💡 `POST_NOTIFICATIONS` hanya diperlukan di **Android 13+ (API 33)**. Di bawah API 33, izin ini otomatis diberikan.

---

## 🔄 Alur Kerja Aplikasi

```
Pengguna Input Nama Kota
        │
        ▼
 Tekan Tombol Task
        │
   ┌────┴────┐
   │         │
One-Time   Periodic
   │         │
   └────┬────┘
        │
WorkManager.enqueue()
        │
        ▼
  Cek Constraints
  (Ada Internet?)
        │
      [YA]
        │
        ▼
  MyWorker.doWork()
        │
  SyncHttpClient
  → OpenWeatherMap API
        │
  Moshi Parse JSON
        │
        ▼
  showNotification()
  (Tampil di Status Bar)
        │
        ▼
  Result.success() / failure()
  → LiveData update UI
```

---

## 📄 Lisensi

```
Copyright 2024 Dicoding Indonesia

Licensed under the Apache License, Version 2.0
```

---

<div align="center">
  <sub>Dibuat sebagai bagian dari pembelajaran <strong>Dicoding — Belajar Pengembangan Aplikasi Android Intermediate</strong></sub>
</div>

