package com.dicoding.myworkmanager

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dicoding.myworkmanager.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

/**
 * # MainActivity
 *
 * Layar utama aplikasi yang berfungsi sebagai **titik masuk (entry point)** dan
 * pusat kendali untuk menjadwalkan pekerjaan latar belakang menggunakan **WorkManager**.
 *
 * ## Mengapa WorkManager?
 * WorkManager adalah solusi yang **direkomendasikan Google** untuk pekerjaan latar belakang
 * yang perlu dijamin akan berjalan, bahkan jika aplikasi ditutup atau perangkat restart.
 * Berbeda dengan `AsyncTask` atau `Thread` biasa, WorkManager memastikan tugas tetap
 * terjadwal meski sistem membatasi proses latar belakang (Doze Mode, App Standby).
 *
 * ## Jenis Task yang Didukung:
 * - **One-Time Task**: Tugas yang hanya berjalan sekali → lihat [startOneTimeTask]
 * - **Periodic Task**: Tugas yang berulang setiap interval waktu → lihat [startPeriodicTask]
 *
 * ## Hubungan dengan File Lain:
 * - **[MyWorker]**: Kelas Worker yang berisi logika pekerjaan sesungguhnya (fetch cuaca).
 * - **AndroidManifest.xml**: Izin `POST_NOTIFICATIONS` dan `INTERNET` wajib dideklarasikan
 *   di sana agar notifikasi dan request jaringan bisa berjalan.
 */
class MainActivity : AppCompatActivity(), View.OnClickListener {

    /**
     * Instance tunggal **WorkManager** yang digunakan sepanjang lifecycle Activity.
     *
     * Menggunakan `lateinit` karena inisialisasi membutuhkan `Context` yang baru
     * tersedia setelah `onCreate()` dipanggil. WorkManager adalah **singleton**,
     * jadi aman digunakan berulang kali tanpa membuat instance baru.
     */
    private lateinit var workManager: WorkManager

    /**
     * Referensi ke **PeriodicWorkRequest** yang sedang aktif.
     *
     * Disimpan sebagai properti kelas karena kita perlu mengaksesnya dari
     * fungsi [cancelPeriodicTask] untuk membatalkan task berdasarkan **ID unik**-nya.
     *
     * ⚠️ NOTE: Variabel ini belum diinisialisasi saat Activity pertama kali dibuat.
     * Memanggil [cancelPeriodicTask] sebelum [startPeriodicTask] akan menyebabkan
     * `UninitializedPropertyAccessException`. Pastikan tombol Cancel hanya aktif
     * setelah task berhasil di-enqueue (lihat logika `btnCancelTask.isEnabled`).
     */
    private lateinit var periodicWorkRequest: PeriodicWorkRequest

    /** Referensi View Binding yang menghindari kebutuhan `findViewById` secara manual. */
    private lateinit var binding: ActivityMainBinding

    /**
     * **Launcher** untuk meminta izin runtime kepada pengguna.
     *
     * ## Mengapa Perlu Ini?
     * Sejak Android 13 (API 33 / TIRAMISU), izin `POST_NOTIFICATIONS` termasuk dalam
     * kategori **"dangerous permission"** yang harus diminta secara eksplisit saat runtime,
     * tidak cukup hanya dideklarasikan di `AndroidManifest.xml`.
     *
     * ## Cara Kerjanya:
     * - `registerForActivityResult` mendaftarkan callback **sebelum** Activity aktif (aman).
     * - Kontrak `ActivityResultContracts.RequestPermission()` menangani alur dialog sistem.
     * - Callback menerima `Boolean`: `true` jika izin diberikan, `false` jika ditolak.
     *
     * ## Hubungan dengan File Lain:
     * - Izin `android.permission.POST_NOTIFICATIONS` wajib ada di **AndroidManifest.xml**.
     * - Izin ini diperlukan agar [MyWorker.showNotification] dapat menampilkan notifikasi
     *   pada perangkat Android 13+.
     *
     * ⚠️ NOTE: Launcher ini **harus** dideklarasikan sebelum `onCreate()` selesai
     * (di luar onCreate). Mendeklarasikannya setelah Activity `STARTED` akan menyebabkan
     * `IllegalStateException`.
     */
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Notifications permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notifications permission rejected", Toast.LENGTH_SHORT).show()
            }
        }

    /**
     * Titik awal lifecycle Activity. Semua inisialisasi utama dilakukan di sini.
     *
     * Urutan operasi penting:
     * 1. `enableEdgeToEdge()` → Konten menggambar hingga tepi layar (termasuk status bar).
     * 2. `ViewBinding` → Meng-inflate layout agar komponen UI bisa diakses dengan aman.
     * 3. `WindowInsets` → Menambahkan padding agar konten tidak tertutup system bar.
     * 4. Minta izin notifikasi → Hanya untuk Android 13+.
     * 5. Inisialisasi WorkManager & pasang listener tombol.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mengaktifkan mode edge-to-edge agar UI terasa modern (konten di balik status bar).
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Menambahkan padding dinamis sesuai ukuran system bar (status bar & navigation bar)
        // agar konten utama tidak tertutup oleh elemen sistem.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Izin notifikasi hanya menjadi "dangerous permission" mulai Android 13 (API 33).
        // Di bawah API 33, izin ini otomatis diberikan jika ada di Manifest.
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // WorkManager.getInstance() mengembalikan singleton — aman dan efisien untuk dipanggil
        // berkali-kali karena tidak membuat instance baru setiap kali.
        workManager = WorkManager.getInstance(this)
        binding.btnOneTimeTask.setOnClickListener(this)
        binding.btnPeriodicTask.setOnClickListener(this)
        binding.btnCancelTask.setOnClickListener(this)
    }

    /**
     * Mendistribusikan event klik ke fungsi yang sesuai berdasarkan ID view.
     *
     * Mengimplementasikan `View.OnClickListener` di level Activity (bukan lambda per tombol)
     * agar semua handler klik terpusat di satu tempat, memudahkan pemeliharaan kode.
     */
    override fun onClick(view: View) {
        when (view.id) {
            R.id.btnOneTimeTask -> startOneTimeTask()
            R.id.btnPeriodicTask -> startPeriodicTask()
            R.id.btnCancelTask -> cancelPeriodicTask()
        }
    }

    /**
     * Membatalkan **Periodic Task** yang sedang berjalan atau terjadwal.
     *
     * `cancelWorkById` membatalkan task berdasarkan **UUID unik** yang di-generate
     * WorkManager saat request dibuat. Ini adalah cara paling presisi untuk membatalkan
     * task tertentu tanpa memengaruhi task WorkManager lainnya di aplikasi.
     *
     * ⚠️ NOTE: Fungsi ini akan crash (`UninitializedPropertyAccessException`) jika
     * dipanggil sebelum [startPeriodicTask] pernah dijalankan, karena `periodicWorkRequest`
     * belum diinisialisasi. Pastikan tombol Cancel hanya bisa diklik setelah task aktif.
     */
    private fun cancelPeriodicTask() {
        workManager.cancelWorkById(periodicWorkRequest.id)
    }

    /**
     * Membuat dan menjadwalkan **Periodic Work Request** — tugas yang berulang secara otomatis.
     *
     * ## Mengapa Periodic Task?
     * Digunakan untuk memperbarui data secara berkala (misalnya: pengecekan cuaca setiap
     * 15 menit) tanpa pengguna perlu membuka aplikasi. WorkManager menjamin task ini
     * akan terus berjalan meskipun aplikasi ditutup atau perangkat di-restart.
     *
     * ## Alur Kerja:
     * 1. **Data**: Membungkus input (nama kota) ke dalam objek `Data` untuk diteruskan ke Worker.
     * 2. **Constraints**: Menetapkan syarat jaringan — task hanya berjalan jika ada koneksi internet.
     * 3. **PeriodicWorkRequest**: Membangun request dengan interval minimum **15 menit**
     *    (batas minimum yang ditetapkan oleh sistem Android untuk mencegah pemborosan baterai).
     * 4. **enqueue**: Mendaftarkan task ke antrian WorkManager.
     * 5. **Observer**: Memantau perubahan status task secara reaktif via `LiveData`.
     *
     * ## Hubungan dengan File Lain:
     * - **[MyWorker]**: Kelas yang akan dieksekusi WorkManager, menerima `EXTRA_CITY` dari `Data`.
     * - **AndroidManifest.xml**: Izin `INTERNET` diperlukan agar constraint jaringan bermakna.
     *
     * ⚠️ NOTE: Interval **15 menit** adalah **batas minimum mutlak** di Android.
     * Sistem bisa saja menjalankan task lebih lambat (terutama di mode Doze atau
     * pada device dengan Battery Optimization aktif).
     */
    private fun startPeriodicTask() {
        // Reset tampilan status agar tidak menumpuk dengan status dari sesi sebelumnya.
        binding.textStatus.text = getString(R.string.status)

        // Membungkus input pengguna ke dalam objek Data yang bisa dibaca oleh Worker.
        // Ini adalah satu-satunya cara aman untuk meneruskan data ke Worker di WorkManager.
        val data = Data.Builder()
            .putString(MyWorker.EXTRA_CITY, binding.editCity.text.toString())
            .build()

        // Constraints memastikan task hanya berjalan dalam kondisi yang tepat,
        // mencegah kegagalan task karena tidak ada jaringan saat eksekusi.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Membangun PeriodicWorkRequest. Worker akan dijalankan setiap ~15 menit
        // selama constraints terpenuhi dan task belum dibatalkan.
        periodicWorkRequest = PeriodicWorkRequest.Builder(MyWorker::class.java, 15, TimeUnit.MINUTES)
            .setInputData(data)
            .setConstraints(constraints)
            .build()

        // Mendaftarkan task ke WorkManager. Setelah ini, WorkManager mengambil alih
        // tanggung jawab eksekusi, bahkan jika aplikasi ditutup.
        workManager.enqueue(periodicWorkRequest)

        // Mengamati perubahan status task secara real-time menggunakan LiveData.
        // `this@MainActivity` sebagai LifecycleOwner memastikan observer otomatis
        // berhenti ketika Activity dihancurkan (mencegah memory leak).
        workManager.getWorkInfoByIdLiveData(periodicWorkRequest.id)
            .observe(this@MainActivity) { workInfo ->
                val status = workInfo?.state?.name
                binding.textStatus.append("\n" + status)

                // Tombol Cancel dinonaktifkan secara default, dan hanya diaktifkan
                // ketika task berada di state ENQUEUED (menunggu giliran eksekusi).
                // State RUNNING tidak dipantau di sini karena periodic task akan
                // kembali ke ENQUEUED setelah setiap eksekusi selesai.
                binding.btnCancelTask.isEnabled = false
                if (workInfo?.state == WorkInfo.State.ENQUEUED) {
                    binding.btnCancelTask.isEnabled = true
                }
            }
    }

    /**
     * Membuat dan menjalankan **One-Time Work Request** — tugas yang hanya dieksekusi sekali.
     *
     * ## Perbedaan dengan Periodic Task:
     * - `OneTimeWorkRequest` digunakan untuk aksi yang dipicu oleh pengguna secara eksplisit
     *   dan tidak perlu diulang secara otomatis (misalnya: "cek cuaca sekarang").
     * - Setelah selesai (state `SUCCEEDED` atau `FAILED`), task ini tidak akan berjalan lagi.
     *
     * ## Alur Kerja:
     * 1. **Data & Constraints**: Sama seperti periodic task — kota sebagai input, jaringan sebagai syarat.
     * 2. **OneTimeWorkRequest**: Dibangun tanpa interval karena hanya berjalan satu kali.
     * 3. **enqueue**: Mendaftarkan ke antrian WorkManager.
     * 4. **Observer**: Memantau status hingga task selesai (SUCCEEDED/FAILED/CANCELLED).
     *
     * ## Hubungan dengan File Lain:
     * - **[MyWorker]**: Menerima data kota, memanggil API OpenWeatherMap, lalu menampilkan notifikasi.
     * - **[MyWorker.EXTRA_CITY]**: Kunci konstan untuk mengambil data kota di dalam Worker.
     *
     * ⚠️ NOTE: Jika pengguna menekan tombol ini berkali-kali secara berurutan, setiap
     * tekanan akan membuat Work Request **baru** yang independen. Gunakan `enqueueUniqueWork`
     * jika ingin memastikan hanya ada satu instance yang berjalan pada satu waktu.
     */
    private fun startOneTimeTask() {
        // Reset tampilan status untuk sesi task baru.
        binding.textStatus.text = getString(R.string.status)

        // Mengemas nama kota dari input pengguna sebagai data yang akan diterima Worker.
        val data = Data.Builder()
            .putString(MyWorker.EXTRA_CITY, binding.editCity.text.toString())
            .build()

        // Task hanya akan dieksekusi jika perangkat terhubung ke jaringan.
        // Jika tidak ada jaringan, WorkManager akan menahan task di antrian (ENQUEUED)
        // hingga koneksi tersedia, bukan langsung gagal.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWorkRequest = OneTimeWorkRequest.Builder(MyWorker::class.java)
            .setInputData(data)
            .setConstraints(constraints)
            .build()

        workManager.enqueue(oneTimeWorkRequest)

        // Observer ini akan menerima update status: ENQUEUED → RUNNING → SUCCEEDED/FAILED.
        // Setelah mencapai state terminal (SUCCEEDED/FAILED/CANCELLED), LiveData tidak
        // akan menghasilkan update baru, sehingga observer ini aman untuk dibiarkan aktif.
        workManager.getWorkInfoByIdLiveData(oneTimeWorkRequest.id)
            .observe(this@MainActivity) { workInfo ->
                val status = workInfo?.state?.name
                binding.textStatus.append("\n" + status)
            }
    }
}