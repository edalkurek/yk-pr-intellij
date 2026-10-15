package com.ykb.review.config

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnActionButton
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.ykb.review.ai.ChatMessage
import com.ykb.review.ai.LLMClient
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.BitbucketRepository
import com.ykb.review.net.Http
import com.ykb.review.review.FindingsPresenter
import com.ykb.review.util.safeMessage
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.plaf.basic.BasicComboBoxRenderer
import javax.swing.table.DefaultTableModel

/**
 * Ayarlar formu. Gizli bilgiler (token / API key) yalnızca kullanıcı yeni değer yazarsa kaydedilir;
 * mevcut değer alanlarda gösterilmez, "Kayıtlı" ipucu ile belirtilir.
 */
class ReviewSettingsComponent {
    lateinit var mainPanel: JPanel
        private set

    val bitbucketUrlField = JBTextField()
    val bitbucketTokenField = JBPasswordField()

    val autoReviewEnabledCheck = JBCheckBox("Auto PR Review'u etkinleştir (reviewer olduğum açık PR'ları arka planda incele ve yorum gönder)")
    val autoReviewIntervalSpinner = JSpinner(SpinnerNumberModel(5, 1, 1440, 1))

    val aiBaseUrlField = JBTextField()
    val aiApiKeyField = JBPasswordField()
    val fastModelCombo = ComboBox(ReviewSettingsState.PRESET_MODELS.toTypedArray()).apply { isEditable = true }
    val complexModelCombo = ComboBox(ReviewSettingsState.PRESET_MODELS.toTypedArray()).apply { isEditable = true }

    val minSeverityCombo = ComboBox(arrayOf("low", "medium", "high")).apply {
        renderer = object : BasicComboBoxRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val severity = value as? String
                val text = when (severity) {
                    "low" -> "Düşük ve üstü (tüm bulgular)"
                    "medium" -> "Orta ve üstü"
                    "high" -> "Yalnızca yüksek"
                    else -> severity?.let { FindingsPresenter.severityLabel(it) } ?: ""
                }
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
    }
    val maxChunkCharsSpinner = JSpinner(SpinnerNumberModel(ReviewSettingsState.DEFAULT_MAX_CHUNK_CHARS, 5_000, 500_000, 5_000))
    val extraInstructionsArea = JBTextArea(4, 40)
    val maskSecretsCheck = JBCheckBox("Diff içindeki şifre / token / secret değerlerini modele göndermeden önce maskele")
    val disableTlsCheck = JBCheckBox("TLS sertifika doğrulamasını devre dışı bırak (yalnızca kurumsal ağda sertifika sorunu varsa)")
    private val tlsWarning = JBLabel("Güvenlik riski: ortadaki-adam (MITM) saldırılarına karşı korumayı kaldırır.", AllIcons.General.Warning, JBLabel.LEFT).apply {
        foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
        font = JBUI.Fonts.smallFont()
    }

    // Arayüz tercihleri
    val publicationModeCombo = ComboBox(
        arrayOf(ReviewSettingsState.PUBLICATION_ASK, ReviewSettingsState.PUBLICATION_IDE, ReviewSettingsState.PUBLICATION_PUBLISH),
    ).apply {
        renderer = object : BasicComboBoxRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val text = when (value) {
                    ReviewSettingsState.PUBLICATION_IDE -> "Yalnızca IDE'de göster (panelden gönderilebilir)"
                    ReviewSettingsState.PUBLICATION_PUBLISH -> "Doğrudan Bitbucket'a yorum yaz"
                    else -> "Her seferinde sor"
                }
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
    }
    val openDiffAfterReviewCheck = JBCheckBox("PR incelemesi bitince değişiklikleri diff görüntüleyicide otomatik aç")
    val showInlaysCheck = JBCheckBox("Editör ve diff'te bulgu satırlarının sonunda kısa açıklama göster")

    val repoInstructionsModel = object : DefaultTableModel(arrayOf("Repo (host/proje/slug)", "Özel Talimat"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 1
    }
    val repoInstructionsTable = JBTable(repoInstructionsModel)

    private val testBitbucketBtn = JButton("Bitbucket Bağlantısını Test Et")
    private val testModelBtn = JButton("Model Bağlantısını Test Et")
    private val bitbucketTestResult = JBLabel()
    private val modelTestResult = JBLabel()
    private val clearCredentialsBtn = JButton("Kayıtlı Kimlik Bilgilerini Sil")

    init {
        extraInstructionsArea.lineWrap = true
        extraInstructionsArea.wrapStyleWord = true
        repoInstructionsTable.emptyText.text = "Henüz repo kuralı yok. + ile ekleyin veya yetkili repolarınızı çekin."

        testBitbucketBtn.addActionListener { testBitbucket() }
        testModelBtn.addActionListener { testModel() }
        clearCredentialsBtn.addActionListener { clearCredentials() }
        tlsWarning.isVisible = false
        disableTlsCheck.addActionListener { tlsWarning.isVisible = disableTlsCheck.isSelected }

        val repoTablePanel = ToolbarDecorator.createDecorator(repoInstructionsTable)
            .setAddAction {
                val key = Messages.showInputDialog(
                    mainPanel,
                    "Repo anahtarı (ör. sdlc.yapikredi.com.tr/PROJ/repo-slug):",
                    "Repo Kuralı",
                    Messages.getQuestionIcon(),
                )
                if (!key.isNullOrBlank()) promptForRepoInstruction(key.trim().lowercase())
            }
            .setEditAction {
                val row = repoInstructionsTable.selectedRow
                if (row >= 0) {
                    val key = repoInstructionsModel.getValueAt(repoInstructionsTable.convertRowIndexToModel(row), 0)?.toString().orEmpty()
                    if (key.isNotBlank()) promptForRepoInstruction(key)
                }
            }
            .setRemoveAction {
                val row = repoInstructionsTable.selectedRow
                if (row >= 0) repoInstructionsModel.removeRow(repoInstructionsTable.convertRowIndexToModel(row))
            }
            .addExtraAction(object : AnActionButton("Yetkili Repolarımı Çek", "Bitbucket'ta erişiminiz olan repolardan seçin", AllIcons.Actions.Download) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = fetchRepositories()
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })
            .setPreferredSize(Dimension(JBUI.scale(500), JBUI.scale(160)))
            .createPanel()

        val repoSectionPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBLabel("<html><b>Repo Bazlı Özel İnceleme Talimatları</b></html>"), BorderLayout.NORTH)
            add(repoTablePanel, BorderLayout.CENTER)
            add(comment("Bir repo PR'ı veya yerel deposu incelenirken genel kurallara ek olarak kullanılır. Çift tıklayarak düzenleyin."), BorderLayout.SOUTH)
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>Bitbucket Bağlantısı</b></html>"))
            .addLabeledComponent("Bitbucket URL:", bitbucketUrlField, 1, false)
            .addLabeledComponent("Access Token:", bitbucketTokenField, 1, false)
            .addComponentToRightColumn(comment("Bitbucket → Profil → HTTP access tokens. IDE'nin güvenli deposunda (PasswordSafe) saklanır."))
            .addComponent(row(testBitbucketBtn, clearCredentialsBtn, bitbucketTestResult))

            .addSeparator()
            .addComponent(JBLabel("<html><b>AI Model ve Servis Ayarları (Bifrost Gateway / OpenAI uyumlu)</b></html>"))
            .addLabeledComponent("AI API Base URL:", aiBaseUrlField, 1, false)
            .addLabeledComponent("API Key (opsiyonel):", aiApiKeyField, 1, false)
            .addLabeledComponent("Hızlı Model (rutin değişiklikler):", fastModelCombo, 1, false)
            .addLabeledComponent("Güçlü Model (kritik değişiklikler):", complexModelCombo, 1, false)
            .addComponentToRightColumn(comment("Güvenlik, transaction ve mimari açıdan hassas değişiklikler otomatik olarak güçlü modele yönlendirilir."))
            .addComponent(row(testModelBtn, modelTestResult))

            .addSeparator()
            .addComponent(JBLabel("<html><b>Auto PR Review</b></html>"))
            .addComponent(autoReviewEnabledCheck)
            .addLabeledComponent("Tarama sıklığı (dakika):", autoReviewIntervalSpinner, 1, false)

            .addSeparator()
            .addComponent(JBLabel("<html><b>Arayüz</b></html>"))
            .addLabeledComponent("İnceleme sonucu:", publicationModeCombo, 1, false)
            .addComponent(openDiffAfterReviewCheck)
            .addComponent(showInlaysCheck)

            .addSeparator()
            .addComponent(JBLabel("<html><b>İnceleme &amp; Güvenlik</b></html>"))
            .addLabeledComponent("Minimum önem seviyesi:", minSeverityCombo, 1, false)
            .addLabeledComponent("Maksimum chunk boyutu (karakter):", maxChunkCharsSpinner, 1, false)
            .addComponentToRightColumn(comment("Büyük diff'ler bu boyutta parçalara bölünür. Küçük değer = daha çok model çağrısı."))
            .addLabeledComponent("Genel özel inceleme kuralları:", JBScrollPane(extraInstructionsArea), 1, true)
            .addComponent(maskSecretsCheck)
            .addComponent(disableTlsCheck)
            .addComponent(tlsWarning)

            .addSeparator()
            .addComponent(repoSectionPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    /** Ayarlar yüklendikten sonra (reset) bağımlı görünümleri eşitler. */
    fun syncDependentState() {
        tlsWarning.isVisible = disableTlsCheck.isSelected
        bitbucketTestResult.text = ""
        bitbucketTestResult.icon = null
        modelTestResult.text = ""
        modelTestResult.icon = null
    }

    private fun comment(text: String): JBLabel = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }

    private fun showResult(label: JBLabel, ok: Boolean, text: String) {
        label.icon = if (ok) AllIcons.General.InspectionsOK else AllIcons.General.Error
        label.text = text
        label.toolTipText = text
        label.foreground = if (ok) UIUtil.getLabelForeground() else JBColor.namedColor("Label.errorForeground", JBColor.RED)
    }

    // ------------------------------------------------------------------ getters

    val fastModel: String get() = (fastModelCombo.editor.item ?: fastModelCombo.selectedItem)?.toString()?.trim().orEmpty()
    val complexModel: String get() = (complexModelCombo.editor.item ?: complexModelCombo.selectedItem)?.toString()?.trim().orEmpty()
    val interval: Int get() = (autoReviewIntervalSpinner.value as Number).toInt()
    val maxChunkChars: Int get() = (maxChunkCharsSpinner.value as Number).toInt()
    val typedBitbucketToken: String get() = String(bitbucketTokenField.password).trim()
    val typedApiKey: String get() = String(aiApiKeyField.password).trim()

    fun repoInstructions(): Map<String, String> {
        if (repoInstructionsTable.isEditing) repoInstructionsTable.cellEditor?.stopCellEditing()
        val result = LinkedHashMap<String, String>()
        for (i in 0 until repoInstructionsModel.rowCount) {
            val key = repoInstructionsModel.getValueAt(i, 0)?.toString()?.trim().orEmpty()
            val value = repoInstructionsModel.getValueAt(i, 1)?.toString()?.trim().orEmpty()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    fun setRepoInstructions(map: Map<String, String>) {
        repoInstructionsModel.rowCount = 0
        map.toSortedMap().forEach { (k, v) -> repoInstructionsModel.addRow(arrayOf<Any>(k, v)) }
    }

    fun updateSecretHints(settings: ReviewSettingsState) {
        bitbucketTokenField.text = ""
        aiApiKeyField.text = ""
        bitbucketTokenField.emptyText.text = if (settings.bitbucketTokenStored || settings.bitbucketToken.isNotBlank()) "Kayıtlı (değiştirmek için yeni değer girin)" else "Bitbucket HTTP access token"
        aiApiKeyField.emptyText.text = if (settings.modelApiKeyStored || settings.aiApiKey.isNotBlank()) "Kayıtlı (değiştirmek için yeni değer girin)" else "Gerekmiyorsa boş bırakın"
    }

    // ------------------------------------------------------------------ actions

    /** Yazılan token varsa onu, yoksa kayıtlı olanı kullanır. */
    private fun bitbucketClient(): BitbucketClient {
        val token = typedBitbucketToken.ifEmpty { SecretStore.get(SecretStore.Key.BITBUCKET_TOKEN).orEmpty() }
        return BitbucketClient(
            bitbucketUrlField.text.trim(),
            token,
            Http.client(30, 60, 30, disableTlsCheck.isSelected),
        )
    }

    private fun <T> withProgress(title: String, block: () -> T): Result<T> {
        var result: Result<T> = Result.failure(IllegalStateException("İşlem tamamlanmadı"))
        val finished = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { result = runCatching(block) },
            title,
            true,
            null,
            mainPanel,
        )
        return if (finished) result else Result.failure(IllegalStateException("İşlem iptal edildi."))
    }

    private fun testBitbucket() {
        withProgress("Bitbucket bağlantısı test ediliyor…") { bitbucketClient().verifyAccess() }
            .onSuccess { showResult(bitbucketTestResult, true, "Bağlantı başarılı · reviewer olduğunuz açık PR: $it") }
            .onFailure { showResult(bitbucketTestResult, false, "Başarısız: ${it.safeMessage()}") }
    }

    private fun testModel() {
        val model = fastModel
        withProgress("Model bağlantısı test ediliyor…") {
            val key = typedApiKey.ifEmpty { SecretStore.get(SecretStore.Key.MODEL_API_KEY).orEmpty() }
            val client = LLMClient(aiBaseUrlField.text.trim(), key, Http.client(30, 90, 30, disableTlsCheck.isSelected))
            client.chat(model, listOf(ChatMessage("user", "Sadece OK yaz.")), 0.0)
        }
            .onSuccess { showResult(modelTestResult, true, "Model yanıt verdi ($model): ${it.trim().take(40)}") }
            .onFailure { showResult(modelTestResult, false, "Başarısız: ${it.safeMessage()}") }
    }

    private fun clearCredentials() {
        val answer = Messages.showYesNoDialog(
            mainPanel,
            "Kayıtlı Bitbucket token ve model API key silinsin mi?",
            "Kimlik Bilgilerini Sil",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        SecretStore.clearAll()
        updateSecretHints(ReviewSettingsState.instance)
        Messages.showInfoMessage(mainPanel, "Kayıtlı kimlik bilgileri silindi.", "YKB AI Review")
    }

    private fun fetchRepositories() {
        var baseUrl = ""
        withProgress("Bitbucket repoları çekiliyor…") {
            val client = bitbucketClient()
            baseUrl = client.webBaseUrl
            client.listRepositories()
        }
            .onFailure { Messages.showErrorDialog(mainPanel, "Repolar listelenemedi: ${it.safeMessage()}", "Bitbucket") }
            .onSuccess { repos -> chooseRepository(baseUrl, repos) }
    }

    private fun chooseRepository(baseUrl: String, repos: List<BitbucketRepository>) {
        if (repos.isEmpty()) {
            Messages.showInfoMessage(mainPanel, "Erişim yetkiniz olan bir repo bulunamadı.", "Bitbucket")
            return
        }
        val keys = repos.map { ReviewSettingsState.repoKey(baseUrl, it.projectKey, it.slug) }.distinct().sorted().toTypedArray()
        val selected = Messages.showEditableChooseDialog(
            "Özel talimat eklemek istediğiniz repoyu seçin:",
            "Repo Seçimi (${keys.size} repo)",
            Messages.getQuestionIcon(),
            keys,
            keys.first(),
            null,
        )
        if (!selected.isNullOrBlank()) promptForRepoInstruction(selected.trim().lowercase())
    }

    private fun promptForRepoInstruction(key: String) {
        var existingRow = -1
        for (i in 0 until repoInstructionsModel.rowCount) {
            if (repoInstructionsModel.getValueAt(i, 0)?.toString().equals(key, ignoreCase = true)) {
                existingRow = i
                break
            }
        }
        val existing = if (existingRow >= 0) repoInstructionsModel.getValueAt(existingRow, 1)?.toString().orEmpty() else ""

        val area = JBTextArea(existing, 6, 50).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
            add(JBLabel("'$key' reposu için özel review talimatları:"), BorderLayout.NORTH)
            add(JBScrollPane(area).apply { preferredSize = JBUI.size(520, 180) }, BorderLayout.CENTER)
            add(comment("Boş bırakıp kaydederseniz bu repo kuralı silinir."), BorderLayout.SOUTH)
        }
        val dialog = com.intellij.openapi.ui.DialogBuilder(mainPanel).apply {
            setTitle("Repo Talimatı")
            setCenterPanel(panel)
            setPreferredFocusComponent(area)
        }
        if (!dialog.showAndGet()) return

        val text = area.text.trim()
        when {
            existingRow >= 0 && text.isEmpty() -> repoInstructionsModel.removeRow(existingRow)
            existingRow >= 0 -> repoInstructionsModel.setValueAt(text, existingRow, 1)
            text.isNotEmpty() -> repoInstructionsModel.addRow(arrayOf<Any>(key, text))
        }
    }

    private fun row(vararg components: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply { components.forEach { add(it) } }
}
