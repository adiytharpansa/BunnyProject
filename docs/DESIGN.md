# Bunny — Dokumen Desain

Bunny dirancang dari nol untuk proyek ini. Tidak ada kode, pola, atau mesin
dari proyek klien YouTube lain yang dipakai — bukan fork, bukan pembungkus
library ekstraktor mana pun. Dokumen ini adalah sumber kebenaran desainnya.

Riset latar (mengapa pasar yang ada tidak cukup): laporan perbandingan 10
kandidat, 10 November 2026 —
`~/workspace/research_notes/bunny-better-than-newpipe-research-20261110-0208/report.md`.

## Prinsip desain

1. **Mesin sendiri.** Semua akses ke YouTube ditulis sendiri di modul
   `:engine` ("Lontar"). Ketergantungan hanya pada library platform umum
   (OkHttp, coroutines, Media3, Compose) — tidak ada kode YouTube pihak ketiga.
2. **UI buta mesin.** Layar hanya mengenal kontrak `ExtractionBackend` dan
   model domain Bunny di `:core`. JSON InnerTube mentah tidak pernah keluar
   dari `:engine`.
3. **Jujur soal batas.** Yang belum bisa, dikatakan belum bisa — di kode, di
   dokumen, dan di dalam aplikasi (status mesin tampil di layar Beranda).
4. **Lokal dulu.** Langganan, riwayat, dan rekomendasi hidup di perangkat.
   Tidak ada server Bunny, tidak ada akun Bunny.

## Lontar — mesin InnerTube orisinal

Lontar berbicara langsung ke **InnerTube**, API yang sama dengan yang dipakai
aplikasi resmi YouTube, dari Kotlin murni:

- **Bootstrap sesi**: mengambil konfigurasi publik dari beranda YouTube
  (API key + visitor data), persis seperti klien web resmi memulai sesi.
- **Persona klien**: satu persona Android resmi (nama, versi, SDK, UA) yang
  konsisten di semua panggilan; header `X-YouTube-Client-*` mengikuti.
- **Jelajah & cari**: `browse` (FEtrending) dan `search` (filter video saja).
  Respons berupa pohon renderer yang berubah-ubah antar versi, jadi parser
  Lontar berjalan rekursif: objek `videoRenderer` di mana pun dikenali
  sebagai video. Token kelanjutan (continuation) diperlakukan buram.
- **Beranda "Campuran Lontar"**: beranda anonim InnerTube terbukti kosong
  untuk pengunjung baru (terverifikasi langsung 2026-11-09), jadi Beranda
  Bunny *by design* adalah campuran: trending lebih dulu, dan bila kosong,
  campuran round-robin pencarian topik (berita, musik, gaming, film,
  podcast) dengan duplikat dan siaran langsung disisihkan.
- **Player**: endpoint `player` dipanggil lewat rantai persona khusus
  putar (terpisah dari persona WEB bidang metadata). Temuan lapangan
  2026-11-10: klien ANDROID_VR mengembalikan set format lengkap dengan URL
  langsung tanpa cipher, sedangkan WEB/TVHTML5 menipiskan streamingData
  sampai tersisa format tercipher — jadi jalur putar tidak membutuhkan
  pemecah cipher. Format adaptif diubah menjadi manifest DASH oleh
  generator milik Lontar sendiri (DashManifestBuilder). Temuan lanjutan
  2026-11-10: gerbang bot (LOGIN_REQUIRED) terikat ke sesi, bukan persona —
  permintaan player wajib membawa visitorData sesi beranda; tanpa itu VR
  ditolak bahkan dari IP bersih, dengan itu playability OK + format
  langsung lengkap (terverifikasi pada video yang gagal di perangkat).
- **Cache respons player** per video, dipakai bersama oleh panggilan detail
  dan stream.

## Fitur khas Bunny

Tiga fitur ini dirancang untuk Bunny dan — per riset 10 kandidat — tidak ada
di NewPipe, LibreTube, PipePipe, FreeTube, Grayjay, atau klien sejenis:

1. **Timbangan — lencana biaya data.** Setiap kartu video menampilkan
   perkiraan ukuran data untuk menontonnya (≈ MB, dari bitrate format ×
   durasi) *sebelum* diketuk. Dipadukan **Jatah**: anggaran kuota bulanan
   yang memperingatkan sebelum putar bila akan terlampaui. Ditujukan untuk
   pengguna yang kuotanya nyata dan terbatas.
2. **Terbit — edisi pagi langganan.** Video baru dari langganan lokal
   dikumpulkan menjadi satu edisi bergaya koran yang terbit sekali sehari:
   dibuka, dibaca sampai habis, selesai. Lawan dari umpan tak berujung.
3. **Chip Alasan.** Setiap video di Beranda dan Terbit membawa alasan
   tampilnya ("Dari langganan: Blender Foundation", "Topik: podcast",
   "Sedang naik daun"). Rekomendasi yang bisa diaudit pengguna, dihitung
   sepenuhnya di perangkat.

Fitur pendukung yang direncanakan: **Panen Bertingkat** — unduhan mengambil
audio dulu (instan, kecil), lalu menaikkan ke video di latar saat Wi-Fi
tersedia; SponsorBlock/RYD/DeArrow sebagai modul REST mandiri.

## Struktur modul

```
:app    UI Compose/Material 3, ViewModel, Hilt, Room, Media3 (nanti)
:core   Model domain + kontrak ExtractionBackend (Kotlin murni)
:engine Lontar — klien InnerTube (OkHttp + org.json), parser, sesi
```

## Roadmap

- **Fase 0** — Kerangka profesional: modul, kontrak, CI, dokumen. ✅
- **Fase 1** — Lontar bidang metadata: sesi, Beranda campuran, pencarian,
  detail video. ✅ *(CI hijau; Beranda terverifikasi hidup di perangkat.)*
- **Fase 2** — Bidang putar: rantai persona player (ANDROID_VR), manifest
  DASH buatan Lontar, rantai putar DASH → HLS → progresif, layanan Media3
  dengan kontrak foreground yang benar, diagnostik pemutaran bawaan. ✅
  *(Terbukti di perangkat 2026-11-10, termasuk putar latar + notifikasi
  lewat MediaController; pemecah cipher tidak diperlukan di jalur ini.)*
- **Fase 3** — Fitur khas: Timbangan + Jatah, Terbit, Chip Alasan,
  langganan lokal + grup, Panen Bertingkat.
  *(Dalam pengerjaan — Timbangan + Jatah, Chip Alasan, langganan lokal,
  dan Terbit masuk lebih dulu; grup langganan dan Panen Bertingkat
  menyusul dalam fase yang sama.)*
- **Fase 4** — Ketahanan: persona bergilir, konfigurasi mesin yang dapat
  diperbarui jarak jauh, telemetri kegagalan putar di perangkat.

## Keputusan tercatat

- 2026-11-10: Fondasi = mesin sendiri (perintah pengguna: rancang sendiri,
  jangan bergantung pada siapa pun, buat yang belum pernah ada).
- 2026-11-10: Repo GitHub bernama **BunnyProject**, privat, standar
  profesional (perintah pengguna).
- Lisensi: hak cipta penuh pemilik untuk sekarang (lihat `LICENSE`);
  keputusan lisensi publik diambil sebelum rilis publik apa pun.
