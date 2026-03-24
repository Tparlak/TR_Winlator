# TR_Winlator (Standalone & All-in-One)

![Header](https://via.placeholder.com/1000x400?text=TR_Winlator+Standalone+v1.0.0)

*(Scroll down for English)*

## Türkçe Detaylar
Bu sürüm tamamen **Bağımsız (Standalone)** bir yapıya kavuşturulmuştur. `imagefs.txz` adlı 1GB'lık RootFS (Kök Dosya Sistemi) dosyası doğrudan APK'nın içine gömülüdür. 

**Ekstra OBB dosyası indirmenize veya sdcard/ dizinine taşımanıza GEREK YOKTUR.** Uygulamayı kurduğunuz an her şey kullanıma hazır olur!

### Özellikler (Features)
- **GlibC v11R2 Tabanlı:** Yüksek performanslı ve stabil Winlator modifikasyonu.
- **Düzeltilmiş 1GB+ Varlık Okuma (openFd):** Standart sürümdeki bellek limitlerine takılma (silent crash) sorunu, File Descriptor okuma yöntemiyle tamamen çözülmüştür.
- **Kapsayıcı (Container) Desteği:** İlk açılışta RootFS çıkarılır ve (+) butonuna basarak anında yeni kapsayıcılar oluşturabilirsiniz.

### Kurulum ve Kullanım
1. [Releases](https://github.com/Tparlak/TR_Winlator/releases) sayfasından en güncel `app-debug.apk` dosyasını indirin.
2. APK'yı kurun ve çalıştırın.
3. Uygulama otomatik olarak sistem dosyalarını cihazınıza yükleyecektir (Ortalama 1-2 dakika sürer).
4. Yükleme tamamlandığında sağ üstteki (+) butonuna tıklayarak yeni sisteminizi kurun!

---

## English Details
This version features the 1GB RootFS image embedded directly inside the APK (**Standalone**). **No external OBB download required.** Simply install the APK, and all system files will extract locally.

### Key Enhancements
- GlibC v11R2 based, high-performance Winlator mod.
- Fixed 1GB+ asset reading (`openFd`), preventing memory boundary crashes on large file extractions during first launch.

### Installation
1. Download the latest `app-debug.apk` from the [Releases](https://github.com/Tparlak/TR_Winlator/releases) page.
2. Install and launch the app.
3. Wait for the initial system extraction to complete.
4. Tap the (+) button on the top right to create your container.