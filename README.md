# YKB Bitbucket AI Code Review - IntelliJ IDEA Plugin

Bu proje, Bitbucket Server / Data Center PR'larını ve yerel Git değişikliklerini doğrudan **IntelliJ IDEA** içerisinden yapay zeka ile denetleyen kurumsal bir JetBrains eklentisidir.

---

## 🌟 Öne Çıkan Özellikler

1. **Tek Model:** Tüm incelemeler ayarlarda seçilen model ile yapılır.
2. **Doğrudan Bitbucket Entegrasyonu (yalnızca Bitbucket Server / Data Center; Bitbucket Cloud desteklenmez):**
   - Açık PR listesi çekme
   - Tek tıkla diff analizi ve inceleme başlatma
   - Otomatik genel özet ve satır içi (inline) yorum gönderme
3. **Yerel Ön İnceleme (Pre-Commit Review):**
   - Commit atmadan önce staged/unstaged/untracked yerel diff'i inceleme ve potansiyel riskleri önceden yakalama.
   - Changes görünümünde seçili class/dosya veya repository'yi; editörde seçili kod aralığını kapsam olarak kullanma.
4. **Hassas Veri Maskeleme:**
   - Token, API anahtarı ve şifrelerin diff üzerinden modele iletilmeden önce otomatik maskelenmesi.
5. **Ortak Talimat Reposu:**
   - Ekip inceleme kuralları Bitbucket'taki ortak bir repoda tutulur; genel, proje ve repo bazında otomatik eşleştirilir (bkz. [Ortak Talimat Reposu](#-ortak-talimat-reposu)).

---

## 📁 Proje Dizin Yapısı

```
ykb-pr-review-intellij/
├── src/
│   ├── main/
│   │   ├── kotlin/com/ykb/review/
│   │   │   ├── actions/          # IDE aksiyonları (ReviewActions: PR ve yerel inceleme tetikleyicileri)
│   │   │   ├── ai/               # ReviewEngine, SmartModelRouter, LLMClient, ReviewPrompt, DiffChunker, DiffFilter, ReviewParser, ReviewMerger
│   │   │   ├── bitbucket/        # BitbucketClient, BitbucketModels, PullRequestUrlParser, RemoteMatcher
│   │   │   ├── config/           # ReviewSettingsState, ayar ekranı, ConfigValidation, SecretStore
│   │   │   ├── local/            # Yerel Git diff'i (LocalGit), inceleme kapsamı (LocalReviewScope), HookCleaner
│   │   │   ├── net/              # Ortak HTTP yardımcıları (Http)
│   │   │   ├── review/           # ReviewCoordinator, PublishService, AutomaticReviewService, CommentFormatter, FindingsPresenter
│   │   │   ├── startup/          # ReviewStartupActivity
│   │   │   ├── ui/               # Tool window, ReviewPanel, FindingsView, PR diff görüntüleyici, editör vurguları, status bar widget
│   │   │   └── util/             # Cancellation, Json
│   │   └── resources/
│   │       └── META-INF/
│   │           └── plugin.xml    # JetBrains Plugin Descriptor
│   └── test/kotlin/com/ykb/review/  # ai, bitbucket, local, review paketlerinin birim testleri
├── build.gradle.kts              # Gradle IntelliJ Plugin konfigürasyonu
├── settings.gradle.kts
├── gradle.properties
├── gradlew.bat
└── SPRINT_PLAN.md                # 5 Aşamalı Sprint Yol Haritası
```

---

## 🛠️ Nasıl Çalıştırılır ve Paketlenir?

### 1. IntelliJ IDEA İçinde Açma
Proje dizinini (`ykb-pr-review-intellij`) IntelliJ IDEA'da **Open -> Proje dizini** seçerek açın. Gradle bağımlılıkları otomatik olarak yüklenecektir.

### 2. Geliştirme Modunda Test Etme (Sandbox IDE)
Terminalden aşağıdaki komutu çalıştırarak eklentinin yüklü olduğu izole bir IntelliJ IDEA oturumu başlatabilirsiniz:
```bash
./gradlew runIde
```

### 3. Testleri Çalıştırma
```bash
./gradlew test
```
> **Not:** Windows'ta proje yolu Türkçe karakter içeriyorsa (örn. `OneDrive\Masaüstü`) Gradle test sınıflarını bulamaz (`ClassNotFoundException`). Projeyi `C:\dev\...` gibi ASCII bir yolda tutun.

### 4. Dağıtım Paketini Hazırlama (.zip)
Eklentiyi şirket içinde veya IDE'ye `Install Plugin from Disk...` ile kurmak üzere paketlemek için:
```bash
./gradlew buildPlugin
```
Üretilen paket: `build/distributions/yk-pr-intellij-<sürüm>.zip`

Sürüm paketleri depoya eklenmez (`.gitignore` içinde `*.zip`); GitHub Releases üzerinden dağıtılır.

---

## ⚙️ Yapılandırma (Settings)

IntelliJ IDEA içerisinde:
**Settings (Tercihler) -> Tools -> YKB AI Code Review**

- **Bitbucket URL:** `https://bitbucket.yapikredi.com.tr` (Bitbucket Server / Data Center adresi; `bitbucket.org` kabul edilmez)
- **Access Token:** Bitbucket HTTP Access Token
- **AI Base URL:** OpenAI uyumlu `/chat/completions` uç noktası sunan sağlayıcı veya kurum içi model gateway URL'i
- **API Key:** LLM API anahtarı
- **Model:** Sağlayıcınızda/gateway'inizde tanımlı model adı
- **Genel özel inceleme kuralları:** Yalnızca sizin IDE'nizde geçerli kişisel kurallar (örn. *"Tüm metotlarda `@Transactional` anotasyonunu doğrula"*).
- **Repo Bazlı Özel İnceleme Talimatları:** Belirli bir repo için yalnızca sizin IDE'nizde geçerli kurallar.
- **Ortak Talimat Reposu:** Ekipçe paylaşılan kuralların bulunduğu Bitbucket reposu (aşağıya bakın).

---

## 📚 Ortak Talimat Reposu

Ekipteki herkesin aynı inceleme kurallarını kullanması için kurallar her geliştiricinin IDE'sine tek tek girilmez; Bitbucket'taki ortak bir repoda Markdown dosyaları olarak tutulur. Eklenti bir PR'ı veya yerel değişikliği incelerken, o repoya ait kuralları bu repodan otomatik okur. Kural değişiklikleri PR ile önerilir ve ekipçe gözden geçirilir; birleştiği anda herkese yansır.

### 1. Repoyu oluşturun

Eklentinin bağlandığı **aynı Bitbucket sunucusunda** yeni bir repo açın (örn. `SDLC` projesi altında `ai-review-rules`). Eklenti dosyaları kullanıcının kendi Bitbucket token'ı ile okuduğu için, eklentiyi kullanacak herkesin bu repoda en az **okuma (Read)** yetkisi olmalıdır.

### 2. Dosyaları klasör düzenine göre ekleyin

Eşleştirme için ayrı bir tanım yapılmaz; **dosyanın bulunduğu yer, hangi repolara uygulanacağını belirler**:

```
ai-review-rules/
├── general.md                    ← tüm repolar
├── projects/
│   ├── PAY.md                    ← PAY projesindeki tüm repolar
│   └── CRM.md                    ← CRM projesindeki tüm repolar
└── repos/
    └── PAY/
        └── payment-api.md        ← yalnızca PAY/payment-api reposu
```

| Dosya | Uygulandığı yer |
|---|---|
| `general.md` | Tüm repolar |
| `projects/<PROJE>.md` | O projedeki tüm repolar |
| `repos/<PROJE>/<repo-slug>.md` | Yalnızca o repo |

Adlandırma kuralları:
- `<PROJE>`, Bitbucket **proje anahtarıdır** ve **BÜYÜK harfle** yazılır (`PAY`, `CRM`).
- `<repo-slug>`, Bitbucket repo URL'sindeki addır ve **küçük harfle** yazılır (`payment-api`).
- Proje anahtarı ile slug'ı repo URL'sinden okuyabilirsiniz: `https://<bitbucket>/projects/PAY/repos/payment-api/...`
- Bir dosya yoksa o katman sessizce atlanır; yalnızca ihtiyaç duyduğunuz dosyaları oluşturun.

### 3. Talimatları yazın

Dosyalar düz Markdown'dır; modele olduğu gibi gönderilir. Kısa, somut ve kontrol edilebilir maddeler en iyi sonucu verir.

`general.md`:
```markdown
- Log'lara müşteri TCKN'si, kart numarası, şifre veya token yazılmamalı.
- Yakalanan exception'lar yutulmamalı; en azından loglanmalı veya yeniden fırlatılmalı.
```

`projects/PAY.md`:
```markdown
- Tutar hesaplarında `double`/`float` yerine `BigDecimal` kullanılmalı.
- Para birimi her tutarla birlikte taşınmalı.
```

`repos/PAY/payment-api.md`:
```markdown
- Ödeme başlatan uç noktalarda idempotency anahtarı kontrol edilmeli.
- Dış ödeme sağlayıcısı çağrılarında timeout tanımlı olmalı.
```

Sınırlar: dosya başına 20.000, tüm ortak talimatlar için toplam 40.000 karakter. Aşan kısım kesilir.

### 4. Eklentide repoyu tanımlayın

**Settings → Tools → YKB AI Code Review → Ortak Talimat Reposu**
- **Repo (PROJE/slug):** `SDLC/ai-review-rules`
- **Dal:** `master` (boş bırakılırsa `master`)
- **Test Et / Yenile:** `general.md`'yi okuyarak bağlantıyı doğrular ve önbelleği temizler.

### Nasıl eşleşir?

`PAY/payment-api` reposundaki bir PR incelenirken talimatlar şu sırayla birleştirilir (genelden özele; en özel kural en sonda yer alır):

1. `general.md`
2. `projects/PAY.md`
3. `repos/PAY/payment-api.md`
4. IDE ayarlarındaki genel kurallar
5. IDE ayarlarındaki repo kuralı

| İncelenen repo | Okunan ortak dosyalar |
|---|---|
| `PAY/payment-api` | `general.md`, `projects/PAY.md`, `repos/PAY/payment-api.md` |
| `PAY/payment-batch` | `general.md`, `projects/PAY.md` (repo dosyası yoksa atlanır) |
| `HR/izin-app` | yalnızca `general.md` |

Yerel incelemelerde proje ve repo, depodaki git remote adresinden (`https://.../scm/pay/payment-api.git` veya `ssh://git@...:7999/pay/payment-api.git`) bulunur. Remote yoksa yalnızca `general.md` ve IDE kuralları uygulanır.

İnceleme özetinde **"Talimatlar: Ortak genel + Ortak repo (PAY/payment-api) + IDE genel"** gibi bir satır, hangi kuralların uygulandığını gösterir.

### Önbellek ve hata durumu

- Okunan dosyalar 10 dakika önbellekte tutulur. Bir değişikliği hemen görmek için ayarlarda **Test Et / Yenile**'ye basın.
- Ortak repoya erişilemezse (ağ, yetki) son başarılı okunan sürüm kullanılır; hiç okunamadıysa inceleme yalnızca IDE kurallarıyla devam eder. İnceleme bu yüzden hiçbir zaman başarısız olmaz.

### Güvenlik

Ortak talimatlar modele "proje özel talimatları" olarak gider ve sistem kurallarını (kanıt şartı, çıktı biçimi, güvenlik kuralları) geçersiz kılamaz. Yine de bu repoya yazma yetkisini sınırlı tutun ve değişiklikleri PR ile gözden geçirin.
