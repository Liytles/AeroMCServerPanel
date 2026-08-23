# AeroMC Server Panel

Minecraft sunucularını üç sağlayıcı modunda yöneten JavaFX masaüstü paneli:

- **Yerel JAR:** Başlat/durdur, canlı konsol, online oyuncular ve ZIP yedekleme.
- **Exaroton:** Resmî API ile sunucuları listeleme, başlatma/durdurma/yeniden başlatma, canlı konsol ve oyuncular.
- **Aternos:** SRV yönlendirmeli Aternos adresleri ve özel portlarla canlı online durum, sürüm, oyuncu ve ping kontrolü; adres hatırlama, 15 saniyelik otomatik yenileme ve resmî panele geçiş.

Exaroton API anahtarı varsayılan olarak yalnızca uygulama belleğinde tutulur. Kullanıcı isterse en az 8 karakterlik bir ana parola belirleyebilir; anahtar PBKDF2-HMAC-SHA256 ile türetilen anahtar ve AES-256-GCM kullanılarak şifrelenir. Ana parola hiçbir yere kaydedilmez. **Ayarlar → Güvenli Kimlik Bilgileri** seçeneği ayrıca Exaroton anahtarını ve Discord webhook'unu cihaz/kullanıcı bağlı AES-256-GCM kasasında tutup sonraki açılışta alanlara geri yazmadan otomatik kullanabilir. Aternos web otomasyonu servis kurallarına aykırı olduğu için kullanılmaz.

## Araçlar merkezi

- PaperMC, Fabric ve Vanilla için resmî kaynakları kullanan tek tık sunucu kurulum sihirbazı
- Açık Minecraft EULA onayı, Java sürüm kontrolü, RAM aktarımı ve Windows/Linux/macOS başlatma betikleri
- Survival, SMP, Creative, SkyBlock temeli ve modlu sunucu şablonları
- `plugin.yml` ve `fabric.mod.json` inceleyen; bozuk JAR, yinelenen kimlik ve eksik bağımlılık bulan uyumluluk tarayıcısı
- Not ekleme, boyut görüntüleme, güvenli dünya geri yükleme ve eski yedekleri kurtarılabilir arşive taşıma
- `server.properties` için görüş mesafesi, simülasyon mesafesi, RAM ve disk yazma önerileri sunan optimizasyon asistanı
- Telefon ve diğer bilgisayarlar için mobil uyumlu uzaktan kontrol ekranı
- Viewer, Moderator ve Admin rolleri; PBKDF2-HMAC-SHA256 parola özetleri, başarısız giriş hız sınırı ve işlem günlüğü

Uygulama genelindeki Türkçe/İngilizce dil tercihi, güvenli otomatik kimlik kasası ve Canlı Harita performans anahtarı, kurulum araçlarından ayrılan ana **Ayarlar** sekmesinde bulunur ve sonraki açılışta korunur. API anahtarı, webhook ve ana parola alanlarında kopyala/kes, sağ tık ve sürükleme engellenir; otomatik değerler arayüz alanına hiç yerleştirilmez. Bu önlemler yanlışlıkla panoya sızmayı ve diskten doğrudan okumayı zorlaştırır ancak aynı kullanıcı yetkisiyle çalışan kötü amaçlı yazılıma karşı mutlak koruma değildir.

## AeroMC Güncelleme Merkezi

**Ayarlar → AeroMC Güncelleme Merkezi**, sabitlenmiş `Liytles/AeroMCServerPanel` GitHub Releases kaynağından kararlı veya beta sürümleri denetler. İşletim sistemine uygun `.exe`, `.deb` veya `.dmg` paketini seçer; indirme ilerlemesini ve sürüm notlarını gösterir. Paket yalnızca GitHub HTTPS adresinden indirilir ve aynı yayındaki `<kurucu-adı>.sha256` dosyasıyla SHA-256 doğrulaması geçerse açılabilir. Kullanıcının `.aeromc-panel` ayarları, kimlik kasaları ve Minecraft sunucu klasörleri güncelleme sırasında değiştirilmez.

Güncelleme yayınlamak için depo ve Releases alanı anonim kullanıcılar tarafından okunabilir olmalıdır. `pom.xml` sürümünü örneğin `3.1.0` yap, üç platform paketini üret ve `v3.1.0` GitHub Release'ine her kurucuyla birlikte paketleme betiklerinin oluşturduğu eş adlı `.sha256` dosyasını yükle. Private depodaki yayınlar kullanıcı tokenı olmadan okunamadığından masaüstü güncelleme merkezi private Release üzerinden çalışmaz.

Uzaktan erişim varsayılan olarak yalnızca `127.0.0.1` üzerinde açılır. LAN seçeneğini sadece güvendiğin özel ağda kullan; bağlantı yerel HTTP olduğundan internete port yönlendirmesi yapma. En az 10 karakterli güçlü bir parola kullan.

## Yönetim merkezi özellikleri

- Yerel sunucuyu açmadan önce JAR, Java, EULA, port, RAM, disk, yedek ve mod/plugin dosyalarını denetleyen **Başlatma Kontrolü**
- Kritik sorunlarda güvenli başlatma engeli; açık kullanıcı onayıyla EULA düzeltme ve uyarılarla devam seçeneği
- Ana panelde favori sunucu kartları ve 30 saniyelik otomatik durum yenileme
- Oyuncu artışı, sunucu kapanması/çökmesi ve tamamlanan yedek için masaüstü bildirimleri
- Son 24 ölçümden mini oyuncu grafiği ve çalışma süresi
- Yerel sunucular için 15/30/60 dakikalık planlı yedek
- Hata, uyarı, oyuncu ve panel satırlarını ayıran renkli konsol
- Tüm oyunculara hızlı mesaj gönderme
- Exaroton hesap kredisi ve sunucu RAM bilgisi
- Resmî API'den adres, durum, yazılım/sürüm, RAM ve hesap kredisini doğrulayan **Exaroton Hazırlık Denetimi**; 12 saniyelik kesin zaman aşımı, başarısızlıkta onaylı denetimsiz başlatma ve Ayarlar'dan tamamen kapatma
- Oyuncular çevrimiçiyken yeniden başlatma onayı ve isteğe bağlı otomatik duyuru
- Seçili sunucunun RAM'inden resmî `1 kredi / GiB / saat` tarifesiyle maliyet ve tahmini kalan süre; ayrıca hesap bakiyesi değişiminden ayrı gözlenen tüketim sunan **Exaroton Kredi Koruması**
- Kalıcı kredi geçmişi/grafiği, ayarlanabilir düşük kredi bildirimi ve yalnızca oyuncusuz sunucuyu güvenli otomatik durdurma
- Düşük kredi eşiğini tamamen açıp kapatma ve isteğe bağlı olarak eşik altında seçili Exaroton sunucusunu duyuruyla otomatik durdurma
- Hesaptaki bütün sunucuları kartlarla gösteren **Exaroton Filo Paneli**: toplam online/çöken sunucu, oyuncu, aktif RAM ve saatlik maliyet özeti
- Sunucu kartından tekil yönetim ve işlemden hemen önce oyuncu sayısını yeniden doğrulayan, onaylı toplu oyuncusuz-sunucu durdurma
- Hedef sunucuya güvenle bağlanan **Exaroton Otomasyon Merkezi**: hafta içi/hafta sonu ve gece yarısını aşabilen ayrı çalışma programları
- Sunucu online olana kadar hazır olma takibi, sınırlı çökme kurtarma denemeleri ve oyuncu gelmezse ayarlanabilir otomatik durdurma
- En yüksek öncelikli günlük/haftalık kredi bütçeleri; sınır dolduğunda duyuruyla durdurma ve bütçe yenilenene kadar otomatik başlatmayı engelleme
- Kullanıcı, otomasyon, kredi koruması, durum geçişi, hata ve hazır olma olaylarını yerelde saklayan Exaroton olay günlüğü; bütün otomasyonları tek düğmeyle kapatma
- **Yönetim** sekmesinden Yerel JAR veya Exaroton sunucusu seçme
- Whitelist, OP, kick, ban, özel mesaj ve geri alma işlemleri
- İşlem onayı ve saat damgalı yönetim günlüğü
- MOTD, oyuncu sınırı, oyun modu, zorluk, PvP ve whitelist ayarları
- Yerel `server.properties` dosyasını içeriğini koruyarak güvenli güncelleme; Exaroton ayarlarını resmî API üzerinden kaydetme

## Pro Araçlar

- Yerel JAR / Exaroton sağlayıcı seçimi; Exaroton sekmesindeki aktif sunucuyla otomatik senkronizasyon
- Yerel sunucuda canlı CPU/RAM; Exaroton WebSocket akışında canlı TPS, tick süresi ve RAM yüzdesi; durum ve oyuncu grafikleri
- RAM yetersizliği, port çakışması, mod/eklenti bağımlılığı, aşırı yük ve çökme satırlarını Türkçe açıklayan log analizcisi
- Temel yapılandırma dosyaları için güvenli editör, otomatik `.bak` kopyası ve atomik kayıt
- Yerel Paper/Spigot eklentileri ve Fabric modları için ekleme, etkinleştirme ve devre dışı bırakma
- Dünya listeleme, ZIP yedekleme, güvenli geri yükleme ve yeni dünya seçimi
- **Görevler & Bildirimler** altında dakika bazlı tek seferlik yedek, yeniden başlatma, durdurma ve duyuru görevleri; Exaroton'un sürekli kuralları için doğrudan Exaroton Otomasyon Merkezi'ne yönlendirme
- Yalnızca Yerel JAR seçiliyken görünen yerel çökme sonrası yeniden başlatma ve RAM uyarısı; Exaroton çökme kurtarmasının tek kaynağı Exaroton Otomasyon Merkezi'dir
- İlk giriş, son görülme, giriş sayısı ve toplam oyun süresini saklayan oyuncu profilleri
- Çökme sonrası otomatik yeniden başlatma ve yüksek RAM bildirimi
- Olay türü filtreleri, renkli embed mesajları, sağlayıcı/sunucu bilgisi ve güvenli `allowed_mentions` kullanan **Discord Bildirim Merkezi**
- Kritik olaylarda isteğe bağlı tek rol etiketi, istenmeyen `@everyone`/rol etiketlerini engelleme, 429 hız sınırında kontrollü yeniden deneme
- Webhook URL'sini oturumluk kullanma, ana paroladan türetilen AES-256-GCM anahtarıyla ayrı kasada şifreli saklama veya Ayarlar'daki cihaz bağlı kasayla alanı doldurmadan otomatik açma
- Whitelist, duyuru, kayıt, yedek ve güvenli kapatmayı birleştiren tek tık bakım modu

## Tek Tık Mod Merkezi

**Kontrol Merkezi → Tek Tık Mod Merkezi**, seçilen sunucunun Minecraft sürümüne ve yazılımına uygun Modrinth projelerini arar.

- Yerel JAR ve Exaroton için sunucu sürümü/loader bilgisini otomatik algılama; gerektiğinde elle seçim
- Fabric, Forge, NeoForge, Quilt modları ile Paper, Purpur, Spigot ve Bukkit eklentileri
- Yalnızca sunucuda çalışabilen sonuçları gösterme ve zorunlu bağımlılıkları otomatik kurma
- İndirilen her JAR için Modrinth SHA-512 ve dosya boyutu doğrulaması
- Yerel kurulumdan önce sunucuya özel `mods`/`plugins` güvenlik yedeği, panel tarafından yönetilen sürüm güncellemesi ve hata halinde geri alma
- Exaroton'a resmî dosya API'siyle doğrulanmış JAR yükleme
- Otomatik yükleme API'si bulunmayan Aternos için projeyi bulduktan sonra resmî mod sayfasına güvenli geçiş

**Güncelleme & Çakışma** sekmesi, panel dışından elle kurulmuş JAR'ları da SHA-512 dosya hash'iyle tanır:

- Kurulu ve hedefe uygun en yeni sürümü karşılaştırır; güncel, güncellenebilir, tanınmayan ve uyumsuz dosyaları ayırır.
- Eksik zorunlu bağımlılık, `incompatible` proje, yinelenen mod kimliği, yanlış Minecraft/loader ve hedef dosya adı çakışmalarını kurulumdan önce gösterir.
- Seçilen güncellemeleri ve gereken bağımlılıkları tek işlemde kesin sürüm çözümüyle hazırlar.
- Kritik çakışma varsa otomatik uygulamayı engeller; sunucu çalışırken mod dosyalarını değiştirmez.
- Yerel sunucuda ZIP yedeği ve atomik geri alma; Exaroton'da yerel ZIP güvenlik kopyası, yeniden yükleme ve hata halinde uzaktan geri alma uygular.

Kurulum tamamlandıktan sonra mod veya eklentinin etkinleşmesi için sunucuyu yeniden başlat.

## Kontrol Merkezi ve sunucu sağlığı

Eski yatay Pro Araçlar sekmeleri, daha rahat bulunabilmeleri için sol menülü **Kontrol Merkezi** altında toplandı. Yerel JAR, Exaroton ve Aternos ekranları da ana menüdeki **Sunucular** bölümüne alındı.

- TPS, RAM, CPU, gecikme, aşırı yük uyarıları ve oturumdaki çökmelerden hesaplanan canlı **0–100 Sunucu Sağlık Puanı**
- Paper 1.21+ için tek tık **Lag Avcısı**: Spark profiler veya sağlık raporu başlatma, konsoldaki rapor bağlantısını otomatik yakalama ve açma
- Başlatma, çökme, oyuncu, yedek, otomasyon, Kriz Modu ve Spark olaylarını saklayan kopyalanabilir **Olay Zaman Çizelgesi**
- Sağlık puanını etkileyen nedenleri anlık gösteren Mükemmel / Sağlıklı / Dikkat / Riskli / Kritik durumları
- Çökmeden önceki son 300 konsol satırını inceleyen; RAM, port, Java, mod, eklenti ve watchdog sorunlarını ayıran **Akıllı Çökme Doktoru**
- Olası şüpheli JAR, güven yüzdesi, kanıt ve güvenli çözüm sırası
- Ayarlanabilir TPS ve RAM eşikleriyle otomatik veya elle etkinleştirilebilen **Kriz Modu**
- Kriz sırasında zamanlanmış ağır görevleri durdurma, `randomTickSpeed` azaltma ve `view-distance` / `simulation-distance` için güvenli geçici değerler hazırlama
- Sunucuyu izinsiz yeniden başlatmayan; değerler toparlanınca normal ayarları ve görevleri geri getiren güvenli çalışma
- Oyuncular için oynama süresi, giriş, ölüm ve ilerleme verilerinden üretilen puan, rütbe ve başarı rozetleri
- Seçilen oyuncunun paylaşılabilir başarı kartı metnini panoya kopyalama

## Uyumluluk ve sunucuya özel yedekler

- Uyumluluk ekranında taranacak yerel sunucu açıkça seçilir; bilinen JAR listesi sunucu değiştiğinde otomatik yenilenir.
- `plugins` ve `mods` klasörleri aynı taramada incelenir. Klasörler boşsa araç artık boş ekran yerine açıklayıcı sonuç gösterir.
- **Araçlar → Kurulum** ile kurulan veya **JAR Seç** üzerinden kullanılan her yerel sunucu kalıcı listeye eklenir.
- Her sunucu kendi `<sunucu klasörü>/backups` alanını kullanır; listeler birbirine karışmaz.
- Seçili ZIP yedeği, yol güvenlik kontrolü ve kullanıcı onayından sonra notuyla birlikte silinebilir.
- Exaroton ve Aternos resmî API'leri dünya yedeği indirmeyi sağlamadığından barındırılan sunucuların yedekleri kendi resmî web panellerinden yönetilir.

## Uzaktan erişim nasıl kullanılır?

1. **Araçlar → Uzaktan Erişim** bölümünde kullanıcı adı, en az 10 karakterli parola ve rol seçerek kullanıcı oluştur.
2. Yalnız bu bilgisayardan kullanacaksan LAN kutusunu kapalı bırak. Telefondan kullanacaksan telefonu aynı Wi-Fi'a bağla ve **Yerel ağdaki telefonlara aç** seçeneğini işaretle.
3. **Uzaktan Erişimi Başlat** düğmesine bas. Aynı bilgisayarda **Tarayıcıda Aç**, telefonda ise ekranda gösterilen adres kullanılır.
4. Tarayıcının giriş penceresine oluşturduğun kullanıcı adı ve parolayı yaz.

`VIEWER` yalnızca durumu görür, `MODERATOR` mesaj/kick/ban kullanabilir, `ADMIN` ise başlatma, durdurma, yeniden başlatma, yedek ve konsol komutlarına erişebilir. LAN erişimini yalnızca güvendiğin özel ağda kullan; internete port yönlendirmesi yapma.

**Güvenlik Günlüğü**, hatalı girişleri, geçici IP engellerini, oluşturulan/silinen kullanıcıları ve uzaktan gönderilen yönetim işlemlerini zaman, kullanıcı, olay, hedef ve sonuç sütunlarıyla gösterir. Parolalar ve API anahtarları günlüğe yazılmaz.

Exaroton seçiliyken canlı durum, ayrılmış RAM, oyuncular, konsol analizi, güvenli metin dosyaları, eklenti/mod yükleme, zamanlanmış yeniden başlatma-durdurma-duyuru, Discord ve bakım modu resmî API üzerinden çalışır. Exaroton API dünya yedeği/geri yükleme ve uzaktaki dosyayı yeniden adlandırma uç noktaları sunmadığından bu üç işlem panelde açık bir uyarıyla Exaroton web paneline yönlendirilir.

## Canlı oyuncu haritası

- Yerel JAR ve seçili Exaroton sunucusuyla otomatik sağlayıcı senkronizasyonu
- Her 5 saniyede sunucu konsolundan oyuncu X/Y/Z ve boyut sorgusu
- Overworld, Nether ve End için ayrı koordinat görünümleri
- Oyuncu renkleri, son 40 konumdan hareket izi ve canlı koordinat tablosu
- Fare tekerleğiyle yakınlaştırma, sürükleyerek kaydırma, otomatik kadraj ve oyuncuya odaklanma
- Harita merkezinde sarı nişangâh ve nişangâhın hedeflediği X/Z koordinatları
- Fare tekerleğine ek olarak `+`, `−` ve yakınlığı sıfırlama kontrolleri

Harita, ek bir Minecraft eklentisi gerektirmeyen canlı koordinat/radar görünümüdür. Dünya bloklarının ve yapıların görsel döşemelerini göstermek için ayrıca BlueMap veya Dynmap gibi bir sunucu eklentisi gerekir.

Canlı Harita'ya ihtiyaç yoksa **Ayarlar → Performans Özellikleri** bölümünden kapatılabilir. Bu ayar yalnızca sekmeyi gizlemez: konum sorgusunu, çizim zamanlayıcısını ve Exaroton dinleyicilerini tamamen durdurur; tercih sonraki açılışta korunur.

## Çalıştırma

Gereksinimler: Java 17 ve Maven.

```bash


```

İlk açılışta **Dosya Seç** ile sunucunun `server.jar`, Paper veya Fabric sunucu JAR dosyasını seç. Panel JAR'ın bulunduğu klasörü sunucu klasörü olarak kullanır.

Oyuncu ve sunucu seçenekleri için **Yönetim** sekmesini aç. Yerel işlemlerde sunucunun çalışıyor olması gerekir. Exaroton işlemlerinde önce **Sunucular → Exaroton** bölümünden hesabı bağlayıp yönetilecek sunucuyu seç. Sağlık, Kriz Modu, Çökme Doktoru ve Başarı Kartları **Kontrol Merkezi** içindedir.

## AeroMC 3.0 kurulum paketleri

Yayın paketleri kendi Java çalışma ortamını içerir; uygulamayı kullanacak kişinin ayrıca Java veya Maven kurmasına gerek yoktur.

- **Windows 10/11:** `AeroMC-3.0.1.exe` dosyasına çift tıkla ve kurulum sihirbazını tamamla.
- **Ubuntu/Debian Linux:** `aeromc_3.0.1-1_amd64.deb` dosyasına çift tıkla veya paket yöneticisiyle kur.
- **macOS:** `AeroMC-3.0.1.dmg` dosyasını aç ve AeroMC'yi Applications klasörüne taşı.

İlk dağıtımlar imzasızdır. Bu nedenle Windows SmartScreen veya macOS Gatekeeper ilk açılışta yayıncı uyarısı gösterebilir. Herkese açık üretim yayını öncesinde Windows Authenticode sertifikası ile imzalama ve Apple Developer ID ile imzalama/noter onayı yapılmalıdır. Öğrenciyim lan ben nereden bulayim 200 doları. Daha projenin 1 starı bile yok. İsteyen kodları inceleyebilir. Kaynak kodu herkese açık — isteyen inceleyip doğrulayabilir. Ancak bu proje açık kaynak lisansıyla değil, tüm hakları saklı (all rights reserved) bir lisansla yayınlanmıştır: kaynağı kopyalamak, değiştirmek veya yeniden dağıtmak için ayrıca yazılı izin gerekir — bkz. LICENSE.txt 

Her işletim sistemi paketi kendi işletim sisteminde üretilir. Proje GitHub'a yüklendiğinde `.github/workflows/release-build.yml`, üç sistemi ayrı makinelerde otomatik derleyerek indirilebilir `AeroMC-Windows`, `AeroMC-Linux` ve `AeroMC-macOS` çıktıları oluşturur. GitHub'da **Actions → Cross-platform release packages → Run workflow** yolu izlenir.

Geliştirici olarak yerel paket üretmek için:

```bash
# Ubuntu/Debian
./scripts/package-linux.sh

# macOS
./scripts/package-macos.sh
```

```powershell
# Windows PowerShell
.\scripts\package-windows.ps1
```

Linux'ta `fakeroot`, Windows'ta WiX Toolset 3 ve her sistemde tam JDK 17+ ile Maven gerekir. Paketler `release/` klasörüne yazılır. Herkese açık Debian yayını için gerçek iletişim adresi `AEROMC_MAINTAINER_EMAIL=destek@alanadiniz` biçiminde verilmelidir; verilmezse yerel paket `aeromc@localhost` kullanır. Beklenmeyen bir uygulama hatasında tanılama kaydı kullanıcı klasöründeki `.aeromc-panel/logs` altında oluşur; bilinen anahtar/parola kalıpları otomatik maskelenir.

Yayın öncesinde [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md), uygulama lisansı [LICENSE.txt](LICENSE.txt) ve bağımlılık bildirimleri [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) kontrol edilmelidir.

## Güvenli yedekleme

Sunucu çalışıyorsa panel önce `save-off` ve `save-all flush` komutlarını gönderir; dünya klasörlerini `backups/` içine ZIP olarak kaydeder ve ardından `save-on` gönderir.
