# AeroMC V4 Roadmap

**Türkçe** · [English](#english)

> AeroMC V4'ün ilk kararlı sürümü 4.0.0 yayımlandı. Bu belge V4 mimarisinin hedeflerini ve sonraki geliştirme yönünü korur.

## V4'ün ana hedefi

AeroMC V4, Yerel JAR, Exaroton, Aternos ve Pterodactyl'i birbirinden kopuk ekranlar olarak değil, aynı sunucu modeli altında çalışan sağlayıcılar olarak ele alacak. Arayüz yalnızca ilgili sağlayıcının desteklediği işlemleri gösterecek; durum, oyuncu, olay, sağlık ve bildirim verileri ortak bir biçimde işlenecek.

## Geliştirme aşamaları

### 1. Birleşik sağlayıcı temeli

- Yerel JAR, Exaroton, Aternos ve Pterodactyl için ortak sunucu kimliği, durum ve yetenek modeli
- Pterodactyl Client API için HTTPS zorlamalı, yönlendirme izlemeyen bağlantı; sunucu/kaynak ve güvenli dosya okuma, güç/komut işlemleri ve kısa ömürlü WebSocket biletiyle canlı konsol
- Başlatma, durdurma, yeniden başlatma, konsol ve oyuncu işlemleri için yetenek tabanlı komutlar
- Sağlayıcı bağlantı hatalarını kullanıcı dostu ve ortak hata sonuçlarına dönüştürme
- Eski 3.x ayarlarını kaybetmeden V4 yapılandırmasına geçiş

### 2. Yeni Ana Panel ve Sunucu Çalışma Alanı

- Tüm sunucuların sağlık, durum, oyuncu, son olay ve yaklaşan görevlerini tek görünümde toplama
- Sunucu seçildiğinde yalnızca desteklenen kontrolleri gösteren çalışma alanı
- Bildirim, olay zinciri, haftalık rapor ve Kriz Modu geçmişini aynı zaman çizelgesinde birleştirme

### 3. Güvenli otomasyon ve kurtarma

- Her otomasyon için önizleme, çalıştırma nedeni ve geri alma/kurtarma bilgisi
- Yedek doğrulama, saklama politikası ve geri yükleme öncesi güvenlik noktası
- Çökme döngüsü, düşük kredi ve kaynak baskısında sağlayıcıya uygun güvenli eylemler

### 4. Uzaktan yönetim ve genişletilebilirlik

- Masaüstü paneliyle aynı yetenek modelini kullanan yenilenmiş uzaktan erişim
- Cihaz oturumu, ayrıntılı yetki ve güvenlik günlüğü
- Yeni sağlayıcıların ana arayüzü değiştirmeden eklenebilmesini sağlayan bağlantı katmanı

## V4 kalite kuralları

- Arayüz işlemleri ağ veya disk beklerken donmayacak.
- Gizli bilgiler düz metin olarak kaydedilmeyecek veya arayüze geri yazılmayacak.
- Sağlayıcı desteklemiyorsa bir kontrol devre dışı görünmek yerine açıklamasıyla gizlenecek ya da uygun alternatif sunulacak.
- 3.x verileri otomatik yedek alınmadan dönüştürülmeyecek.
- Her yeni motor için çevrimdışı smoke testi bulunacak.

---

## English

> AeroMC V4's first stable release, 4.0.0, is available. This document remains the architectural roadmap for the V4 line.

## Main V4 goal

AeroMC V4 will treat Local JAR, Exaroton, Aternos, and Pterodactyl as providers implementing one server model instead of unrelated screens. The interface will expose only capabilities supported by the selected provider, while status, players, events, health, and notifications use shared representations.

## Development stages

### 1. Unified provider foundation

- Shared server identity, status, and capability models for Local JAR, Exaroton, Aternos, and Pterodactyl
- HTTPS-enforced, no-redirect Pterodactyl Client API connection with server/resource and safe-file reads, power/command actions, and a live console using short-lived WebSocket tickets
- Capability-based start, stop, restart, console, and player commands
- Consistent user-facing results for provider and connection failures
- Migration to V4 configuration without losing existing 3.x settings

### 2. New Dashboard and Server Workspace

- One view for fleet health, status, players, recent incidents, and upcoming tasks
- A selected-server workspace that displays only supported controls
- One timeline combining notifications, incident chains, weekly reports, and Crisis Mode history

### 3. Safe automation and recovery

- Preview, execution reason, and recovery information for every automation
- Backup verification, retention policies, and pre-restore safety points
- Provider-aware safe actions for crash loops, low credit, and resource pressure

### 4. Remote management and extensibility

- Refreshed remote access using the same capability model as the desktop panel
- Device sessions, granular permissions, and a security audit trail
- A connector layer that allows new providers without rebuilding the main interface

## V4 quality rules

- The interface must stay responsive during network and disk operations.
- Secrets must never be stored in plain text or written back into visible fields.
- Unsupported capabilities must be hidden with an explanation or replaced with a suitable alternative.
- 3.x data must not be migrated without an automatic backup.
- Every new engine must have an offline smoke test.
