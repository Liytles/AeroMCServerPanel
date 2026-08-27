# AeroMC kurulum ve veri konumları

AeroMC uygulama dosyalarıyla kullanıcı verilerini ayrı tutar. Güncelleme veya uygulamayı kaldırma işlemi kullanıcının sunucularını ve `.aeromc-panel` verilerini kendiliğinden silmez.

## Uygulama dosyaları

| Sistem | Varsayılan kurulum | İçerik |
| --- | --- | --- |
| Windows | `%LOCALAPPDATA%\AeroMC` | `AeroMC.exe`, uygulama JAR'ları, kütüphaneler ve gömülü Java çalışma ortamı. Kurulum sihirbazında başka konum seçilebilir. |
| Linux | `/opt/aeromc` | `/opt/aeromc/bin/AeroMC`, `/opt/aeromc/lib/app` ve Java çalışma ortamı `/opt/aeromc/lib/runtime`. |
| macOS | `/Applications/AeroMC.app` | Uygulama paketi, JAR/kütüphaneler ve `Contents/runtime` altındaki gömülü Java çalışma ortamı. |

Windows paketi kullanıcı başına kurulur; yönetici hesabındaki `Program Files` alanını kullanmaz. Windows masaüstü ve Başlat menüsü, Linux uygulama menüsü için yalnız kısayol/girdi oluşturulur. macOS DMG içindeki uygulama kullanıcı tarafından Applications klasörüne taşınır.

## Kullanıcıya özel AeroMC verileri

`~`, oturum açmış kullanıcının ev klasörüdür:

- Windows: `C:\Users\<kullanıcı>`
- Linux: `/home/<kullanıcı>`
- macOS: `/Users/<kullanıcı>`

AeroMC özellikler kullanıldıkça `~/.aeromc-panel/` altında şu dosyaları oluşturabilir:

| Yol | Amaç |
| --- | --- |
| `config.properties` | Seçili sunucu JAR'ı, RAM, görünüm, güvenlik ve güncelleme tercihleri. |
| `ui.properties` | Türkçe/İngilizce arayüz tercihi. |
| `favorites.properties` | Ana panel favorileri ve kart tercihleri. |
| `aternos-address.txt` | Son kullanılan Aternos adresi. |
| `exaroton.token` | Ana parolayla şifrelenen isteğe bağlı Exaroton anahtar kasası. |
| `auto-exaroton.secret` | Cihaz/kullanıcı bağlı otomatik Exaroton kasası. |
| `auto-pterodactyl.secret` | Cihaz/kullanıcı bağlı otomatik Pterodactyl Client API anahtarı kasası. |
| `discord-webhook.secret` | Ana parolayla şifrelenen isteğe bağlı Discord webhook kasası. |
| `auto-discord.secret` | Cihaz/kullanıcı bağlı otomatik Discord kasası. |
| `discord-notifications.properties` | Discord olay filtreleri ve gizli olmayan bildirim ayarları. |
| `exaroton-credit-history.csv` | Kredi ölçüm geçmişi. |
| `exaroton-credit-guard.properties` | Kredi eşiği ve koruma tercihleri. |
| `exaroton-automation.properties` | Exaroton otomasyon kuralları. |
| `exaroton-automation-events.tsv` | Exaroton olay günlüğü. |
| `player-history.properties` | Oyuncu istatistikleri ve başarı verileri. |
| `scheduled-jobs.properties` | Tek seferlik yerel görevler. |
| `event-timeline.log` | Kontrol Merkezi olay zaman çizelgesi. |
| `remote-users.properties` | Uzaktan erişim kullanıcılarının tuzlanmış parola özetleri ve rolleri. |
| `security.log` | Uzaktan erişim güvenlik/işlem günlüğü. |
| `remote-tls.p12` | HTTPS uzaktan erişimin RSA özel anahtarı ve yerel sertifikası (PKCS#12). |
| `remote-tls.secret` | TLS anahtar deposunun rastgele parolası; desteklenen sistemlerde yalnız kullanıcı okuyabilir. |
| `remote-tls.crt` | Tarayıcıdaki SHA-256 parmak iziyle karşılaştırılabilen açık sertifika. |
| `logs/crash-*.log` | Maskelenmiş uygulama çökme tanılamaları. |
| `updates/` | İndirilen ve SHA-256 doğrulaması geçen AeroMC kurulum paketleri. |
| `exaroton-content-backups/<sunucu>/` | Exaroton mod/eklenti güncellemesi öncesi güvenlik ZIP'leri. |

Dosyaların çoğu ilk açılışta değil, ilgili özellik ilk kez kaydedildiğinde oluşturulur. Kimlik kasaları açık metin anahtar veya webhook saklamaz.

## Minecraft sunucusu klasöründeki dosyalar

Kullanıcının elle seçtiği mevcut `server.jar` kendi klasöründe kalır; AeroMC onu uygulama kurulum klasörüne taşımaz. Tek Tık Kurulum kullanılırsa kullanıcının seçtiği hedef klasörde şunlar hazırlanabilir:

- `server.jar`
- `server.properties`
- `eula.txt`
- `.aeromc-server.properties` (Minecraft sürümü ve loader bilgisi)
- `start.bat` ve `start.sh`
- Sunucu çalışınca Minecraft'ın kendi `world`, `logs`, `plugins`, `mods` ve diğer dosyaları

AeroMC işlemlerine göre sunucu klasöründe ayrıca şunlar oluşabilir:

- `backups/backup-*.zip` ve isteğe bağlı yedek notları
- `backups/archive/` eski yedek arşivi
- `.aeromc-content-backups/` mod/eklenti işlem öncesi ZIP'leri
- `plugins/.aeromc-modrinth.properties` veya `mods/.aeromc-modrinth.properties` yönetilen Modrinth kayıtları
- Düzenlenen güvenli yapılandırma dosyasının `.bak` kopyası
- `server.properties.optimization-backup` ve `server.properties.crisis-backup`
- Geri yükleme öncesinde `restore-safety-*` veya `<dünya>.pre-restore-*` kurtarma klasörleri

Geçici indirme ve hazırlama dosyaları başarılı işlemden sonra temizlenir. Kullanıcının Minecraft dünyaları yalnızca açık onaylı yedek/geri yükleme işlemleri sırasında değiştirilir.
