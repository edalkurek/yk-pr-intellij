# YKB Bitbucket AI Code Review - IntelliJ IDEA Plugin

Bu proje, Bitbucket Server / Data Center PR'larını ve yerel Git değişikliklerini doğrudan **IntelliJ IDEA** içerisinden yapay zeka ile denetleyen kurumsal bir JetBrains eklentisidir.

---

## 🌟 Öne Çıkan Özellikler

1. **Çift Katmanlı Model Mimarisi (Tiered AI Engine):**
   - **Tier 1 (Hızlı Model - örn. `gpt-4o-mini`, `claude-3-5-haiku`, `gemini-1.5-flash`):** Rutin kod değişiklikleri, trivial kontroller, metadata ve dokümantasyon analizi.
   - **Tier 2 (Güçlü Model - örn. `claude-3-5-sonnet`, `gpt-4o`, `o1`, `gemini-1.5-pro`):** Güvenlik (SQLi, Auth, Token, Leakage), concurrency/transaction, karmaşık mimari ve çok dosyalı backend değişiklikleri.
   - **Smart Model Router:** Değişiklikleri diff içeriği, dosya tipi ve hassasiyetine göre otomatik olarak en uygun modele yönlendirir.
2. **Doğrudan Bitbucket Entegrasyonu:**
   - Açık PR listesi çekme
   - Tek tıkla diff analizi ve inceleme başlatma
   - Otomatik genel özet ve satır içi (inline) yorum gönderme
3. **Yerel Ön İnceleme (Pre-Commit Review):**
   - Commit atmadan önce staged/unstaged yerel diff'i tek tıkla inceleme ve potansiyel riskleri önceden yakalama.
4. **Hassas Veri Maskeleme:**
   - Token, API anahtarı ve şifrelerin diff üzerinden modele iletilmeden önce otomatik maskelenmesi.

---

## 📁 Proje Dizin Yapısı

```
yk-pr-intellij/
├── src/
│   ├── main/
│   │   ├── kotlin/com/ykb/review/
│   │   │   ├── actions/          # TriggerReviewAction, LocalReviewAction
│   │   │   ├── ai/               # Multi-Tier Engine, SmartRouter, LLMClient, Prompt, Chunker, Filter
│   │   │   ├── bitbucket/        # BitbucketClient, Models, DTOs
│   │   │   ├── config/           # Settings State, UI Component & Configurable
│   │   │   ├── review/           # CommentFormatter, Result Aggregator
│   │   │   └── ui/               # ToolWindowFactory, ReviewPanel (PR List & Findings)
│   │   └── resources/
│   │       └── META-INF/
│   │           └── plugin.xml    # JetBrains Plugin Descriptor
│   └── test/kotlin/com/ykb/review/ai/  # SmartRouter, DiffFilter, Parser Testleri
├── build.gradle.kts              # Gradle IntelliJ Plugin konfigürasyonu
├── settings.gradle.kts
├── gradle.properties
├── gradlew.bat
└── SPRINT_PLAN.md                # 5 Aşamalı Sprint Yol Haritası
```

---

## 🛠️ Nasıl Çalıştırılır ve Paketlenir?

### 1. IntelliJ IDEA İçinde Açma
Proje dizinini (`yk-pr-intellij`) IntelliJ IDEA'da **Open -> Proje dizini** seçerek açın. Gradle bağımlılıkları otomatik olarak yüklenecektir.

### 2. Geliştirme Modunda Test Etme (Sandbox IDE)
Terminalden aşağıdaki komutu çalıştırarak eklentinin yüklü olduğu izole bir IntelliJ IDEA oturumu başlatabilirsiniz:
```bash
./gradlew runIde
```

### 3. Testleri Çalıştırma
```bash
./gradlew test
```

### 4. Dağıtım Paketini Hazırlama (.zip)
Eklentiyi şirket içinde veya IDE'ye `Install Plugin from Disk...` ile kurmak üzere paketlemek için:
```bash
./gradlew buildPlugin
```
Üretilen paket: `build/distributions/yk-pr-intellij-1.0.0.zip`

---

## ⚙️ Yapılandırma (Settings)

IntelliJ IDEA içerisinde:
**Settings (Tercihler) -> Tools -> YKB AI Code Review**

- **Bitbucket URL:** `https://bitbucket.yapikredi.com.tr`
- **Access Token:** Bitbucket HTTP Access Token
- **Varsayılan Proje (Key):** örn. `PROJ`
- **Varsayılan Depo (Slug):** örn. `account-service`
- **AI Base URL:** `https://api.openai.com/v1` (veya kurum içi model gateway URL'i)
- **API Key:** LLM API anahtarı
- **Hızlı Model (Tier 1):** `gpt-4o-mini`
- **Güçlü Model (Tier 2):** `claude-3-5-sonnet-20241022` (veya `gpt-4o`)
- **Proje Özel Kuralları:** Takıma veya projeye özel kurallar (örn. *"Tüm metotlarda `@Transactional` anotasyonunu doğrula"*).
