# Bunny

Bunny adalah klien YouTube untuk Android yang ditulis dengan Kotlin. UI aplikasi dan engine akses YouTube dipisahkan melalui kontrak di modul `:core`; engine internalnya bernama **Lontar**.

> **Status:** masih dalam pengembangan. Ringkasan di bawah membedakan kemampuan yang sudah ada di kode dari fitur yang belum tersedia di UI.

## Yang sudah ada

- **Beranda:** menampilkan campuran video dari pencarian topik yang disusun oleh Lontar; duplikat dan siaran langsung disisihkan.
- **Metadata video:** Lontar mengambil dan mengurai data feed serta detail video melalui InnerTube. Engine juga memiliki endpoint pencarian video, tetapi aplikasi belum menyediakan layar pencarian.
- **Pemutaran:** layar video menggunakan Media3/ExoPlayer. Kode pemutaran menyiapkan sumber DASH (termasuk manifest yang dibentuk Lontar), HLS, atau progresif, lalu mencoba sumber berikutnya bila pemutaran gagal.
- **Sesi dan diagnostik:** layanan MediaSession mendukung pemutaran latar dengan notifikasi media; layar pemutar juga menampilkan diagnostik pemutaran.

Ketersediaan pemutaran tetap bergantung pada respons dan format stream yang tersedia untuk video dari YouTube.

## Belum tersedia di UI

- Layar pencarian, meskipun engine Lontar sudah menyediakan operasi pencarian.
- Langganan/edisi **Terbit**, **Timbangan** dan **Jatah**, **Chip Alasan**, serta unduhan.

## Struktur proyek

| Modul | Tanggung jawab |
| --- | --- |
| `:app` | Aplikasi Android, UI Jetpack Compose, ViewModel, dan pemutaran Media3. |
| `:core` | Model domain dan kontrak `ExtractionBackend` yang dipakai UI dan engine. |
| `:engine` | Lontar: sesi InnerTube, permintaan feed/detail/stream, serta parser. |

## Build dan tes

Prasyarat: JDK 17, Android SDK platform 34, dan Gradle 8.7. Repo ini belum menyertakan `gradle-wrapper.jar`, jadi jalankan perintah berikut dengan Gradle 8.7 yang sudah terpasang:

```bash
gradle lint testDebugUnitTest assembleDebug --stacktrace
```

Workflow GitHub Actions dikonfigurasi untuk menjalankan lint, unit test, dan build APK debug pada push maupun pull request. Laporan dan APK debug diunggah sebagai artifact selama 7 hari.

## Hak cipta

Hak cipta © 2026 adiytharpansa. All rights reserved. Lihat [`LICENSE`](LICENSE) untuk ketentuan yang berlaku.
