# Perbaikan halaman Shorts — Bunny

Salin isi zip ini ke root repo (struktur folder sama), timpa file yang ada.

## File
- `app/src/main/java/com/bunny/app/MainActivity.kt`
- `app/src/main/java/com/bunny/app/presentation/screens/shorts/ShortsScreen.kt`
- `app/src/main/java/com/bunny/app/presentation/screens/shorts/ShortsOverlay.kt`
- `app/src/main/res/values/shorts_extra_strings.xml` (baru: `shorts_mute`, `shorts_unmute`, `shorts_search`)

## Yang diperbaiki
1. **Video tidak penuh layar** (pita hitam atas/bawah)
   - MainActivity: di tab Shorts inset atas tidak lagi dipakai, video menembus sampai tepi atas.
   - ShortsScreen: `PlayerView` memakai `RESIZE_MODE_ZOOM` untuk video tegak (9:16), `FIT` untuk video mendatar (otomatis lewat `onVideoSizeChanged`).
2. **Bar navigasi bawah hilang di Shorts**
   - MainActivity: `barVisible` kini juga true saat video Shorts diputar inline (`shortsInlineShown`). Pindah tab tidak perlu keluar dulu.
   - Posisi bar dikembalikan penuh saat pindah ke tab selain Beranda (`barsFraction` direset).
3. **Teks tertimpa video terang**
   - Bayangan teks (`ShortsTextShadow`) pada judul, kanal, meta, dan label rel.
   - Gradasi atas 150dp (0,5) dan bawah 340dp (0,85) sedikit diperkuat.
4. **Rel kanan nyaris menyentuh tepi**
   - Kolom tombol dikunci 68dp, tombol bulat 48dp, label satu baris dengan ellipsis; seluruh kolom jadi area ketuk.
5. **Tombol Langganan oranye** (warna `primary` tema) agar selaras dengan Beranda; status "Berlangganan" tetap tembus pandang.
6. **Progress bar**: area sentuh 32dp, menebal jadi 8dp dan berwarna oranye saat diseret.
7. **Tombol atas** (suara, cari, menu): turun di bawah status bar, latar bundar tipis, area sentuh 44dp, deskripsi aksesibilitas ditambahkan.
8. **Tombol suara baru** (bisukan/nyalakan). Volume pemutar dikembalikan ke penuh saat keluar dari Shorts.

## Sudah ada di kode sebelumnya (tidak diubah)
Ketuk jeda/lanjut, ketuk dua kali = suka + hati, tahan = 2x, judul bisa dilebarkan, sheet komentar, jeda otomatis dan lipat ke mini player saat pindah tab.

## Tambahan (jumlah komentar + pemanasan Short berikutnya)
Dua ini menyentuh file di luar folder Shorts, jadi dipasang lewat skrip.
Dari root repo jalankan:

    python3 apply_patch.py

Skrip memeriksa tiap potongan cocok tepat satu kali; kalau tidak, berhenti tanpa mengubah apa pun pada file itu. Yang diubah:
- `core/.../model/VideoComment.kt`: `CommentsPage` dapat field `totalCountText`.
- `engine/.../InnertubeParser.kt`: `parseCommentsCountText()` membaca `commentsHeaderRenderer.countText`; dipakai di `parseCommentsPage`. Tanpa angka = null (tidak dikarang).
- `app/.../PlayerViewModel.kt`: `CommentsUiState.totalCountText`, diisi saat `loadComments`; fungsi baru `warmNext(id)`.

Efeknya di Shorts: label tombol Komentar menampilkan angka dari server (mis. "1,2 rb"); bila tidak terbaca, tetap "Komentar". Saat satu Short aktif, Short berikutnya dipanaskan (`sabrCoordinator.warm` + `prewarmMedia`, yang sama dengan antrean rekomendasi).

Batas yang jujur:
- Bentuk `commentsHeaderRenderer.countText` belum saya uji pada jawaban YouTube asli (tidak ada akses jaringan). Dibuat defensif, tapi cek di perangkat; bila angka tidak muncul, kirim log "komentar" dari Diagnostik.
- Pemanasan hanya menyiapkan metadata (dan media di jaringan tak berbayar). Pemutarnya tetap satu instance bersama, jadi ini bukan preload dua pemutar.
- Urutan pasang: jalankan `apply_patch.py` DULU, baru salin file Kotlin dari zip (ShortsScreen sekarang memanggil `warmNext` dan `totalCountText`).

## Catatan
Kode ini belum saya kompilasi (tidak ada Gradle/SDK di lingkungan saya). Jalankan `gradle assembleDebug`; bila ada error impor (mis. ikon `VolumeUp`/`VolumeOff` dari material-icons-extended), kabari saya.
