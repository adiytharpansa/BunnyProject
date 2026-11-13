# Youready? — patch ikon, nama, dan dokumen (timpa ke root proyek BunnyProject)

Struktur zip sama dengan repo: ekstrak ke root proyek dan timpa file yang ada.

Aplikasi (`app/src/main/res/`):
- `values/strings.xml` — app_name, home_title, nama channel notifikasi, nama profil, 3 teks bantuan: Bunny → Youready?
- `values/ic_launcher_colors.xml` — warna ubin ikon (#F2F2F5).
- `drawable/ic_launcher_foreground.xml` — ikon baru (tombol hitam + segitiga putih).
- `drawable/ic_launcher_monochrome.xml` — BARU, ikon bertema Android 13+.
- `mipmap-anydpi-v26/ic_launcher.xml` dan `ic_launcher_round.xml` — menambah `<monochrome>`.
- `drawable/ic_bunny_logo.xml` — logo header Beranda/Live/Kanal = ikon baru.
- `drawable/ic_notification.xml` — ikon notifikasi media (siluet putih).

Dokumen: `README.md`, `PRIVACY.md`, `docs/DESIGN.md` — nama produk jadi Youready?.

Sengaja tidak diubah: package `com.bunny...`, `applicationId`, `Theme.Bunny`, nama kelas (`BunnyApplication`,
`BunnyHeader`, ...), file `ic_bunny_logo`, ID channel `bunny_playback`, nama SharedPreferences/DB (`bunny_*`,
`bunny.db`), `rootProject.name`, nama repo. Mengubahnya merusak build atau menghapus data pengguna.
AndroidManifest.xml sudah memakai @mipmap/ic_launcher, @mipmap/ic_launcher_round, dan @string/app_name.

Tambahan: `ikon-playstore-512.png`, `ikon-pratinjau-1024.png`, `ikon-sumber.svg`.
