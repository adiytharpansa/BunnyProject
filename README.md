# Bunny

Klien YouTube untuk Android yang dirancang dan dibangun **dari nol** — bukan
fork, dan tanpa library ekstraktor YouTube pihak ketiga. Mesinnya, **Lontar**,
adalah klien InnerTube yang ditulis sendiri untuk proyek ini.

> Status: **Fase 1 dalam pengerjaan** — kerangka + mesin metadata Lontar.
> Pemutaran penuh tiba di Fase 2. Lihat [`docs/DESIGN.md`](docs/DESIGN.md).

## Fitur khas

Fitur yang dirancang khusus untuk Bunny (tidak ada di klien sejenis):

- **Timbangan** — setiap video menampilkan perkiraan biaya datanya (≈ MB)
  sebelum diketuk, plus **Jatah**: anggaran kuota bulanan dengan peringatan
  sebelum putar.
- **Terbit** — langganan lokal diterbitkan sebagai satu edisi pagi bergaya
  koran: dibuka, habis, selesai. Bukan umpan tak berujung.
- **Chip Alasan** — setiap rekomendasi membawa alasannya ("Dari langganan: …",
  "Topik: …"), dihitung di perangkat dan bisa diaudit pengguna.

## Arsitektur

```
                 ┌─────────────────────────────┐
   Compose UI ──►│  ExtractionBackend (kontrak)│◄── :core
                 └──────────────┬──────────────┘
                                │ implements
                 ┌──────────────▼──────────────┐
                 │  Lontar (:engine)           │
                 │  sesi • browse • search     │
                 │  player • parser renderer   │
                 └──────────────┬──────────────┘
                                │ HTTPS (InnerTube)
                                ▼
                            YouTube
```

| Modul     | Isi                                                        |
|-----------|------------------------------------------------------------|
| `:app`    | UI Compose/Material 3, ViewModel, Hilt, Room, Media3       |
| `:core`   | Model domain Bunny + kontrak `ExtractionBackend` (Kotlin)  |
| `:engine` | Lontar — klien InnerTube orisinal (OkHttp + org.json)      |

UI tidak pernah menyentuh JSON InnerTube; mesin tidak pernah menyentuh
Compose. Keduanya hanya bertemu di kontrak `:core`.

## Stack

Kotlin 1.9.24 • AGP 8.5.2 • Gradle 8.7 • Jetpack Compose (Material 3) •
Hilt 2.51.1 • Room 2.6.1 • Media3 1.4.0 • Coil 2.7.0 • OkHttp 4.12.0
compileSdk/targetSdk 34 • minSdk 26 • Java 17

## Membangun

Syarat: JDK 17 dan Android SDK platform 34.

```bash
./gradlew lint testDebugUnitTest assembleDebug
```

CI GitHub Actions menjalankan perintah yang sama pada setiap push dan
pull request, dan mengunggah laporan + APK debug sebagai artifact.

## Roadmap

- **Fase 0** — Kerangka profesional: modul, kontrak, CI, dokumen. ✅
- **Fase 1** — Lontar bidang metadata: sesi, Beranda campuran, pencarian, detail.
- **Fase 2** — Bidang putar: pemecah cipher Lontar, rantai HLS → DASH →
  progresif, layanan Media3.
- **Fase 3** — Fitur khas: Timbangan + Jatah, Terbit, Chip Alasan, Panen Bertingkat.
- **Fase 4** — Ketahanan: persona bergilir, konfigurasi jarak jauh, telemetri.

Rincian desain dan keputusan: [`docs/DESIGN.md`](docs/DESIGN.md).

## Lisensi

Copyright (c) 2026 adiytharpansa. All rights reserved — lihat
[`LICENSE`](LICENSE). Ketentuan lisensi akan diputuskan sebelum rilis publik.
