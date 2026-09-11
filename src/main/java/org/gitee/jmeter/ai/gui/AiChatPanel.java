package org.gitee.jmeter.ai.gui;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.net.URI;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.gitee.jmeter.ai.intellisense.InputBoxIntellisense;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolEvent;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnHandle;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.presenter.TurnSubscriber;
import org.gitee.jmeter.ai.gui.render.MarkdownParserHolder;
import org.gitee.jmeter.ai.gui.render.UiThemeUtil;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.selection.SelectionListener;
import org.gitee.jmeter.ai.selection.SelectionSnapshot;
import org.gitee.jmeter.ai.selection.SelectionTracker;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.ClaudeService;

import org.apache.jorphan.gui.JMeterUIDefaults;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.TextUtils;
import org.gitee.jmeter.ai.utils.VersionUtils;
import org.gitee.jmeter.ai.service.OpenAiService;
import org.gitee.jmeter.ai.service.provider.ProviderRegistry;
import org.gitee.jmeter.ai.service.provider.AiServiceFactory;
import org.gitee.jmeter.ai.tracing.TracedAiService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel for interacting with AI to generate and modify JMeter test plans.
 * Now uses AgentLoop for full agent capabilities (tools, memory, skills).
 */
public class AiChatPanel extends JPanel
        implements PropertyChangeListener, TurnSubscriber {
    private static final Logger log = LoggerFactory.getLogger(AiChatPanel.class);
    private static final String REPO_URL = "https://github.com/azhengzz/JMeter-AI-Agent-Plugin";

    /**
     * 当前面板实例(单实例,由 {@link AiMenuItem} 懒创建)。供关闭整合在深度提炼成功后
     * 清空消息区使用——面板未创建时为 null(此时 agentLoop 也未初始化,提炼链路不会走到清空)。
     */
    private static volatile AiChatPanel INSTANCE;

    // UI components (kept for backward compatibility)
    private JTextPane chatArea;
    // Wraps chatArea; held as a field so the smart-scroll helpers can read/set the vertical
    // scrollbar (auto-scroll-to-bottom while the user is pinned to the tail).
    private JScrollPane chatScrollPane;
    private JTextArea messageField;
    private JButton sendButton;
    private JComboBox<String> modelSelector;
    // Agent components
    private AgentLoop agentLoop;
    private ClaudeService claudeService; // Keep for model loading
    private OpenAiService openAiService; // Keep for model loading
    private AiService currentAiService; // Track current service

    // Store the base font sizes for scaling
    private float baseChatFontSize;
    private float baseMessageFontSize;

    // Component managers
    private final MessageProcessor messageProcessor;

    // Vertical split pane for drag-to-resize between chat area and input area
    private JSplitPane verticalSplitPane;

    // 渐进展示过工具调用的回合 id 集合（per-turn）：并行活回合交叠（换血后退役 loop
    // 的回合与当前 loop 的回合）时按回合身份归属「是否已渐进显示」，兄弟回合的进度
    // 不得吞掉本回合的工具摘要、也不得使已渐进显示过的回合重复补显
    private final Set<Long> progressiveToolCallTurnIds = new HashSet<>();
    // Separate Stop button (visible during agent processing)
    private JButton stopButton;

    /**
     * 会话渲染代数：/new、"+"、关闭整合清空时 +1（统一经 {@link #advanceRenderEpoch}）。
     * {@link #onTurnEvent} 通知时捕获当前值，EDT 上经 {@link #dispatch} 比对——不符即
     * 旧会话的迟到渲染，丢弃。关两类窗口：重置恰逢回合完成（signalCancel 对已完成
     * future no-op）时排在其后的结论投递；工具批在跑（join 不响应 interrupt）时
     * 重置后落地的 TOOL_CALL 进度。都在 EDT 上读写，volatile 仅兜底。
     */
    private volatile int conversationGeneration;

    /**
     * 活回合 id 集合（{@link TurnHandle#id()}，进程级单调）：{@code dispatch} 的
     * PROGRESS/终态过滤依据——id 不在集合内的投递即武装前早到或 {@code /new} 后迟到，
     * 丢弃。TURN_STARTED 分支与 {@link #adoptRunningIpcTurnIfNeeded} 领养写入，任一
     * 终态（TURN_COMPLETED/TURN_CANCELLED）移除。只在 EDT 读写。
     */
    private final Set<Long> liveTurnIds = new HashSet<>();

    /**
     * loading 指示武装位：{@link #armActiveTurn} 置位，{@link #removeLoadingIndicator}
     * 确认移除（或确认不在文档）后清零——未武装时直接跳过，免去每条 PROGRESS/终态
     * 都做一遍全文档 O(N) 文本扫描。BadLocationException 路径保持武装以便下次重试。
     * 与 liveTurnIds 同一批 EDT 读写（arm/remove 调用点全在事件派发路径上）。
     */
    private boolean loadingIndicatorArmed;

    // Selection context bar (current JMeter element + focused control)
    private SelectionContextBar selectionContextBar;
    private JCheckBox injectContextCheckBox;
    private SelectionListener selectionTrackerListener;

    /**
     * Constructs a new AiChatPanel.
     */
    public AiChatPanel() {
        INSTANCE = this; // 单实例注册,供关闭整合提炼成功后清空消息区
        // Initialize services (keep for model loading)
        claudeService = new ClaudeService();
        openAiService = new OpenAiService();

        // 回合事件订阅挂工厂级表（早于首个 getAgentLoop——见 AgentLoopFactory 注释）：
        // 模型切换换血 loop 后订阅不丢，懒创建面板对在跑回合的后续事件照常可达
        AgentLoopFactory.addTurnSubscriber(this);

        // Initialize AgentLoop with ClaudeService as the default AI service
        initializeAgentLoop();
        // 面板懒创建：委派/CLI 回合可能先于面板存在——构造完成时领养在跑回合
        //（invokeLater 排队，等 UI 字段全部就绪后执行，见方法注释）
        adoptRunningIpcTurnIfNeeded();

        messageProcessor = new MessageProcessor();

        // Register for UI refresh events (for zoom functionality)
        UIManager.addPropertyChangeListener(this);

        // Set up the panel layout
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 600));
        setMinimumSize(new Dimension(350, 400));
        setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        // Initialize model selector with loading state
        modelSelector = new JComboBox<>();
        modelSelector.addItem(null); // Add empty item while loading
        modelSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                    boolean cellHasFocus) {
                if (value == null) {
                    return super.getListCellRendererComponent(list, "Loading models...", index, isSelected,
                            cellHasFocus);
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });

        // Load models in background
        loadModelsInBackground();

        // Add a listener to handle model changes
        modelSelector.addActionListener(e -> {
            String selectedModel = (String) modelSelector.getSelectedItem();
            if (selectedModel != null) {
                log.info("Model selected from dropdown: {}", selectedModel);

                // Parse the model ID to extract provider and model name
                // Format: "provider:model" or just "model"
                String modelName = selectedModel;
                if (selectedModel.contains(":")) {
                    String[] parts = selectedModel.split(":", 2);
                    String provider = parts[0];
                    modelName = parts[1];

                    // Set the model in the appropriate service
                    // Note: We pass the FULL model ID (with prefix) so OpenAiService can detect the provider
                    switch (provider) {
                        case "openai", "deepseek", "zhipu", "moonshot", "minimax", "langcat", "ollama" -> {
                            openAiService.setModel(selectedModel);  // Pass full ID with prefix
                            log.info("Using {} provider for model: {}", provider, modelName);
                        }
                        default -> {
                            // Anthropic (provider tag "anthropic:"): needs the bare model id.
                            claudeService.setModel(modelName);
                            log.info("Using Anthropic provider for model: {}", modelName);
                        }
                    }
                } else {
                    // No provider prefix, assume Anthropic
                    claudeService.setModel(selectedModel);
                    log.info("Using Anthropic provider for model: {}", selectedModel);
                }

                // Switch the AI service based on the selected model
                switchAiService();
            }
        });

        // Create a panel for the chat area with header
        JPanel chatPanel = new JPanel(new BorderLayout());
        Color borderColor = getThemeColor("Component.borderColor", UIManager.getColor("Separator.foreground"));
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, borderColor));

        // Create a header panel for the title and new chat button
        JPanel headerPanel = createHeaderPanel();
        chatPanel.add(headerPanel, BorderLayout.NORTH);

        // Initialize chat area
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        chatArea.setContentType("text/html");
        // Use configured font size if set, otherwise use system default font size
        Font defaultFont = UIManager.getFont("TextField.font");
        int configuredFontSize = AiConfig.getChatFontSize();
        int fontSize = configuredFontSize > 0 ? configuredFontSize : defaultFont.getSize();
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), fontSize);
        largerFont = UiThemeUtil.ensureCjkSupport(largerFont);
        chatArea.setFont(largerFont);
        messageProcessor.setBaseFont(largerFont);
        // Store the base font size for scaling
        baseChatFontSize = largerFont.getSize2D();

        // Apply theme-aware background + StyleSheet. Also re-applied on Look and Feel change
        // (see propertyChange) so the chat follows JMeter's light/dark themes. The body rule
        // deliberately omits a CSS "background" — the JTextPane component background set here
        // provides the chat area's base color, which repaint applies instantly on theme switch
        // without re-parsing the HTML view tree (Swing caches parsed view attributes).
        applyChatTheme();

        // Set default paragraph attributes for left alignment
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet leftAlign = new SimpleAttributeSet();
        StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);

        // Add keyboard shortcut for undo (Cmd+Z on Mac, Ctrl+Z on Windows/Linux)
        InputMap inputMap = chatArea.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = chatArea.getActionMap();

        // Define the key stroke based on the platform - using modern API instead of
        // deprecated Event.META_MASK
        KeyStroke undoKeyStroke;
        KeyStroke redoKeyStroke;
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("mac")) {
            // Mac (Cmd+Z for undo, Cmd+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        } else if (osName.contains("linux")) {
            // Linux (Ctrl+Z for undo, Ctrl+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        } else {
            // Windows (Ctrl+Z for undo, Ctrl+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        }

        inputMap.put(undoKeyStroke, "undoAction");
        actionMap.put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Undo/Redo functionality is now handled by AgentLoop tools
                // Use the agent's undo capability or type @undo in the chat
                try {
                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            "Undo is available through AgentLoop. Type 'undo' in the chat or use the appropriate tool.",
                            Color.BLUE, false);
                } catch (BadLocationException ex) {
                    log.error("Error displaying message", ex);
                }
            }
        });

        // Add keyboard shortcut for redo
        inputMap.put(redoKeyStroke, "redoAction");
        actionMap.put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Undo/Redo functionality is now handled by AgentLoop tools
                // Use the agent's redo capability or type @redo in the chat
                try {
                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            "Redo is available through AgentLoop. Type 'redo' in the chat or use the appropriate tool.",
                            Color.BLUE, false);
                } catch (BadLocationException ex) {
                    log.error("Error displaying message", ex);
                }
            }
        });

        chatScrollPane = new JScrollPane(chatArea);
        chatScrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chatPanel.add(chatScrollPane, BorderLayout.CENTER);

        // Wire smart auto-scroll: while the viewport is pinned to the bottom, each appended
        // message scrolls the latest content into view; once the user scrolls up, appends leave
        // their position untouched until they return to the bottom.
        messageProcessor.setAutoScroll(this::isChatAtBottom, this::scrollToBottom);

        // Create the bottom panel with model selector and input controls
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        // Add model selector to the bottom panel
        // modelPanel uses BorderLayout: WEST holds "Model: " + selector,
        // CENTER holds the selection context bar so it stretches to the right.
        JPanel modelPanel = new JPanel(new BorderLayout(8, 0));
        JPanel modelLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel modelLabel = new JLabel("Model: ");
        modelLeft.add(modelLabel);
        modelLeft.add(modelSelector);
        modelPanel.add(modelLeft, BorderLayout.WEST);

        // Selection context bar: shows current JMeter element + focused control
        selectionContextBar = new SelectionContextBar();
        modelPanel.add(selectionContextBar, BorderLayout.CENTER);

        // Toggle: whether to inject current selection into UserMessage sent to LLM
        injectContextCheckBox = new JCheckBox("ToAI", SelectionTracker.isInjectToContextEnabled());
        injectContextCheckBox.setToolTipText("When checked, each message automatically appends the currently selected JMeter element info (type/name/id/focused field) to the context so the AI is aware of it.");
        injectContextCheckBox.setMargin(new Insets(0, 4, 0, 0));
        injectContextCheckBox.addItemListener(e ->
                SelectionTracker.setInjectToContextEnabled(e.getStateChange() == ItemEvent.SELECTED));
        modelPanel.add(injectContextCheckBox, BorderLayout.EAST);

        bottomPanel.add(modelPanel, BorderLayout.NORTH);

        // Create the input panel with message field and send button
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));

        // Initialize message field
        messageField = new JTextArea(3, 20);
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setFont(largerFont);

        // Store the base font size for scaling
        baseMessageFontSize = largerFont.getSize2D();
        Color inputBorderColor = getThemeColor("Component.borderColor", Color.LIGHT_GRAY);
        messageField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(inputBorderColor),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));

        // Setup intellisense for command suggestions
        new InputBoxIntellisense(messageField);

        // Add key listener for Enter to send message, Shift+Enter for newline
        messageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    if (e.isShiftDown()) {
                        messageField.insert("\n", messageField.getCaretPosition());
                    } else {
                        sendMessage();
                    }
                }
            }
        });

        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputPanel.add(messageScrollPane, BorderLayout.CENTER);

        // Initialize send button
        sendButton = new JButton("Send");
        sendButton.setFont(new Font(sendButton.getFont().getName(), Font.BOLD, 12));
        sendButton.setFocusPainted(false);
        sendButton.setOpaque(true);
        sendButton.addActionListener(e -> sendMessage());

        // Initialize stop button (hidden by default, shown during agent processing)
        stopButton = new JButton("■");  // ■ character
        stopButton.setFont(new Font(stopButton.getFont().getName(), Font.BOLD, 10));
        stopButton.setFocusPainted(false);
        stopButton.setOpaque(true);
        stopButton.setForeground(new Color(180, 40, 40));
        stopButton.setBackground(new Color(255, 210, 210));
        stopButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 80, 80), 1, true),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        stopButton.setToolTipText("Stop the current AI task");
        stopButton.setVisible(false);
        stopButton.addActionListener(e -> stopActiveTask());

        // Button panel: Send (top) + Stop (bottom) vertical layout
        // Buttons expand to fill full height and stretch with split pane drag
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        Dimension maxButton = new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        sendButton.setMaximumSize(maxButton);
        stopButton.setMaximumSize(maxButton);
        sendButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        stopButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        buttonPanel.add(sendButton);
        buttonPanel.add(Box.createVerticalStrut(4));
        buttonPanel.add(stopButton);
        inputPanel.add(buttonPanel, BorderLayout.EAST);

        bottomPanel.add(inputPanel, BorderLayout.CENTER);

        // Create vertical split pane to allow resizing between chat area and input area
        chatPanel.setMinimumSize(new Dimension(0, 100));
        bottomPanel.setMinimumSize(new Dimension(0, 80));

        verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chatPanel, bottomPanel);
        verticalSplitPane.setResizeWeight(0.9);
        verticalSplitPane.setDividerLocation(0.9);
        verticalSplitPane.setContinuousLayout(true);
        verticalSplitPane.setBorder(null);
        add(verticalSplitPane, BorderLayout.CENTER);

        // Display welcome message
        displayWelcomeMessage();

        // Subscribe to selection tracker (L1: tree node, L2: focused control)
        installSelectionTracker();
    }

    /**
     * Subscribe to {@link SelectionTracker} so the {@link SelectionContextBar}
     * reflects the current JMeter element selection and editor-panel focus.
     *
     * <p>{@code SelectionTracker.install()} is idempotent: it is normally installed
     * by {@code SelectionInitCommand} on JMeter's ADD_ALL event, but we call it
     * here as a fallback in case the panel is created before that event fires.
     */
    private void installSelectionTracker() {
        SelectionTracker.install();
        selectionTrackerListener = new SelectionListener() {
            @Override
            public void onComponentSelected(SelectionSnapshot snapshot) {
                SwingUtilities.invokeLater(() -> selectionContextBar.update(snapshot));
            }

            @Override
            public void onElementFocused(SelectionSnapshot snapshot) {
                SwingUtilities.invokeLater(() -> selectionContextBar.update(snapshot));
            }
        };
        SelectionTracker.addListener(selectionTrackerListener);
        // Sync current selection state immediately so the bar isn't empty on first open
        SelectionTracker.fireInitialSnapshot(selectionTrackerListener);
    }

    /**
     * Creates the header panel with title and new chat button.
     * 
     * @return The header panel
     */
    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        Color headerBorderColor = getThemeColor("Separator.foreground", Color.LIGHT_GRAY);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, headerBorderColor),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        headerPanel.setBackground(UIManager.getColor("Panel.background"));

        // Add a title to the left side of the header panel
        JLabel titleLabel = new JLabel("Gitee Ai - JMeter Agent v" + VersionUtils.getVersion());
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 14));

        // Title + Star link grouped on the left so the star sits right of the version.
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titlePanel.setOpaque(false);
        titlePanel.add(titleLabel);
        titlePanel.add(createStarLinkButton());
        headerPanel.add(titlePanel, BorderLayout.WEST);

        // Create the "New Chat" button with a plus icon
        JButton newChatButton = new JButton("+");
        newChatButton.setToolTipText("Start a new conversation");
        newChatButton.setFont(new Font(newChatButton.getFont().getName(), Font.BOLD, 16));
        newChatButton.setFocusPainted(false);
        newChatButton.setMargin(new Insets(0, 8, 0, 8));
        Color buttonBorderColor = getThemeColor("Component.borderColor", Color.LIGHT_GRAY);
        newChatButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(buttonBorderColor, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));

        // Add action listener to reset the conversation
        newChatButton.addActionListener(e -> startNewConversation());

        // Add the button to the right side of the header panel
        headerPanel.add(newChatButton, BorderLayout.EAST);

        return headerPanel;
    }

    /**
     * Build the "⭐ Star" hyperlink-style button that opens the project repo.
     * Rendered as an inline link: no border, no fill, blue text, hand cursor.
     */
    private static JButton createStarLinkButton() {
        JButton starButton = new JButton("⭐ Star");
        starButton.setBorderPainted(false);
        starButton.setContentAreaFilled(false);
        starButton.setFocusPainted(false);
        starButton.setOpaque(false);
        starButton.setMargin(new Insets(0, 2, 0, 2));
        starButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        starButton.setForeground(new Color(9, 105, 218));
        starButton.setFont(new Font(starButton.getFont().getName(), Font.PLAIN, 13));
        starButton.setToolTipText("Star the project on GitHub");
        starButton.addActionListener(e -> openRepoUrl());
        return starButton;
    }

    private static void openRepoUrl() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(REPO_URL));
            } else {
                log.warn("Desktop browsing is not supported on this platform");
            }
        } catch (Exception ex) {
            log.error("Failed to open repo URL: {}", REPO_URL, ex);
        }
    }

    /**
     * Loads the available models in the background.
     */
    private void loadModelsInBackground() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                String provider = AiConfig.getDefaultProvider();
                String model = AiConfig.getDefaultModel();
                String modelId = provider + ":" + model;
                log.info("Model selector using global config: {}", modelId);
                return List.of(modelId);
            }

            @Override
            protected void done() {
                try {
                    List<String> models = get();
                    modelSelector.removeAllItems();

                    String globalModelId = AiConfig.getDefaultProvider() + ":" + AiConfig.getDefaultModel();

                    for (String model : models) {
                        modelSelector.addItem(model);
                    }

                    if (modelSelector.getItemCount() > 0) {
                        modelSelector.setSelectedIndex(0);
                        String selectedModel = (String) modelSelector.getSelectedItem();
                        updateRawServiceForModel(selectedModel);
                        switchAiService();
                        log.info("Model selector set to: {}", selectedModel);
                    }
                } catch (Exception e) {
                    log.error("Failed to load models", e);
                }
            }
        }.execute();
    }

    /**
     * Initialize the AgentLoop with the appropriate AI service.
     */
    private void initializeAgentLoop() {
        try {
            // Get the default AI service based on current model selection
            // During construction, modelSelector may not be initialized yet
            AiService aiService;
            if (modelSelector == null) {
                // 构造期取默认模型服务：走 AiServiceFactory 缓存（含 LangSmith 包装），
                // 与 IpcServer.resolveAgentLoop 的预热用同一裸 model → 同一缓存实例 →
                // 同一 AgentLoop 单例。面板是懒创建的（用户首次打开才构造），若此处换掉
                // 单例，IPC 正在跑的委派/CLI 回合会被孤儿化：其通知发在旧 loop 上
                // （旧 loop 的 presenter 为 null，全部静默丢失），STOP 经新 loop 恒
                // hasActiveRun=false 无法终止。currentAiService 同指工厂实例：模型加载
                // 完成后 switchAiService 比对 newService != currentAiService 时，选中
                // 默认模型则不重建。
                AiService factoryService = null;
                try {
                    factoryService = AiServiceFactory.createService(AiConfig.getDefaultModel());
                } catch (Exception e) {
                    log.warn("Default model service unavailable, falling back to panel-local ClaudeService", e);
                }
                if (factoryService != null) {
                    aiService = factoryService;
                    currentAiService = factoryService;
                } else {
                    aiService = TracedAiService.wrap(claudeService);
                    currentAiService = claudeService;
                }
            } else {
                aiService = getAiServiceForCurrentModel();
            }

            agentLoop = AgentLoopFactory.getAgentLoop(aiService);

            if (agentLoop == null) {
                log.warn("AgentLoop is disabled or failed to initialize. Some features may not work.");
            } else {
                // IPC 回合（委派/CLI）与 re-publish 孤儿回合的呈现均走工厂级回合事件
                // 订阅（构造器 addTurnSubscriber，见 dispatch）。此处不再注册任何
                // loop 级监听器。
                log.info("AgentLoop initialized successfully");
            }
        } catch (Exception e) {
            log.error("Failed to initialize AgentLoop", e);
        }
    }

    // ==== TurnSubscriber：回合事件流呈现（唯一显示通道） ====
    // 订阅挂工厂级表（构造器 AgentLoopFactory.addTurnSubscriber），模型切换换血 loop
    // 后仍存活。会话键过滤（非当前实例会话不派发）已由 AgentLoop.dispatchTurnEvent 完成。

    /**
     * 事件入口：通知线程不保证（ipc-worker / loop 线程 / EDT / 本地提交线程）。
     * 通知时快照会话代数（/new 翻转后到达的旧会话事件整段丢弃）；EDT 上零跳直派，
     * 否则 invokeLater。
     */
    @Override
    public void onTurnEvent(TurnEvent event) {
        final int generation = conversationGeneration;
        if (SwingUtilities.isEventDispatchThread()) {       // 当前已在EDT线程
            dispatch(event, generation);
        } else {
            SwingUtilities.invokeLater(() -> dispatch(event, generation));      // 投递到 EDT 队列排队
        }
    }

    /**
     * EDT 上的单入口分发：活回合集合（liveTurnIds）过滤全面接管——本地/IPC/孤儿
     * 回合同一渲染路径；INJECTED 无条件渲染。COMMAND_RESULT 仅
     * LOCAL_PANEL 源渲染（CLI/委派命令结果走其对端界面——HTTP 信封）。
     */
    private void dispatch(TurnEvent event, int generation) {
        if (generation != conversationGeneration) {
            return; // /new 后迟到：旧会话事件不得渲染进新聊天区
        }
        TurnHandle turn = event.turn();
        switch (event.kind()) {
            case TURN_STARTED -> {
                liveTurnIds.add(turn.id());
                // REPUBLISH 无 You 回显（原 INJECTED 事件已给过注入回显；echoText 为 null）
                if (turn.origin() != TurnOrigin.REPUBLISH) {
                    appendYouLine(turn.echoText());
                }
                armActiveTurn();
            }
            case PROGRESS -> {
                if (liveTurnIds.contains(turn.id())) {
                    handleProgressNow(event.progress(), turn.id());
                }
            }
            case TURN_COMPLETED -> {
                // 远程 /new 清屏（空闲路径）：不可见 IPC 命令回合无 STARTED（不在活回合
                // 集合），但其 cmdNew 已把会话数据清空——面板转录须随之清空翻代数，
                // 否则显示态与 session jsonl 永久分叉（「面板即将清理」的前提对
                // 远程重置同样成立）。本地 /new 不经此（handleNewCommand 先清屏再提交）。
                if (!turn.origin().isLocalPanel() && "/new".equals(turn.echoText())) {
                    clearTranscriptForRemoteReset();
                    return;
                }
                if (!liveTurnIds.remove(turn.id())) {
                    return; // 未领养的早到/迟到终态：无对应武装，不渲染不复位
                }
                handleAgentResponse(event.response(), turn.id());
            }
            case TURN_CANCELLED -> {
                if (!liveTurnIds.remove(turn.id())) {
                    return;
                }
                // 指示删除与按钮复位同判据（面板视角无活回合）：交叠活回合下被取消回合
                // 的终态不得清掉兄弟回合仍在用的 loading 指示。不再查
                // agentLoop.hasActiveRun——终态已派发而注入槽未摘的窗口内会误判
                // 「仍在跑」，且漏退役 loop 上的在跑回合
                if (liveTurnIds.isEmpty()) {
                    removeLoadingIndicator();
                    setButtonToSendMode();
                }
                appendCancelLine(event.cause(), turn.origin());
            }
            case INJECTED -> {
                // 本地注入回显统一走事件，不经面板自渲染/嗅探；
                // IPC 前缀（[from cli] 等）天然区分来源
                try {
                    messageProcessor.appendStyled(chatArea.getStyledDocument(),
                            "[Injected] You: " + event.message(), new Color(0x00, 0x80, 0x00), Font.ITALIC);
                } catch (BadLocationException e) {
                    log.error("Error appending injected message", e);
                }
            }
            case REJECTED_BUSY -> {
                try {
                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            "Session busy: a turn is already running and rejected this message"
                                    + " (queue full or delegation); retry later.",
                            getThemeColor("Label.disabledForeground", Color.GRAY), false);
                } catch (BadLocationException e) {
                    log.error("Error appending busy-reject notice", e);
                }
            }
            case COMMAND_RESULT -> {
                if (!event.origin().isLocalPanel()) {
                    // 远程 /new 清屏（忙期路径）：Phase 2 同步命令在调用方线程执行
                    // cmdNew，会话数据已清空——面板转录须随之清空翻代数（同
                    // TURN_COMPLETED 分支的空闲路径口径）
                    if ("/new".equals(event.message())) {
                        clearTranscriptForRemoteReset();
                    }
                    return; // CLI/委派命令结果留在发起方对端界面（HTTP 信封）
                }
                appendYouLine(event.message());
                handleAgentResponse(event.response(), null);
            }
        }
    }

    /**
     * You 回显行（TURN_STARTED 的 echoText / 本地命令的 raw）。文档为空（刚清屏的
     * /new）时不带前导换行——首块前多一个 {@code \n} 会渲染成顶部空白行。
     */
    private void appendYouLine(String text) {
        try {
            boolean emptyDoc = chatArea.getStyledDocument().getLength() == 0;
            messageProcessor.appendMessage(chatArea.getStyledDocument(),
                    (emptyDoc ? "" : "\n") + "You: " + text, null, false);
        } catch (BadLocationException e) {
            log.error("Error appending turn user message", e);
        }
    }

    /**
     * 回合开始前把界面置为「进行中」：按钮切到 Stop，聊天区末尾加一个加载提示。
     * 加载提示只需出现一次（loadingIndicatorArmed 拦截重复追加）。
     */
    private void armActiveTurn() {
        setButtonToStopMode();
        if (loadingIndicatorArmed) {
            return;
        }
        loadingIndicatorArmed = true;
        try {
            messageProcessor.appendLoadingIndicator(chatArea.getStyledDocument(),
                    getThemeColor("Label.disabledForeground", Color.GRAY));
        } catch (BadLocationException e) {
            log.error("Error adding loading indicator for turn", e);
        }
    }

    /**
     * 取消终止提示行（显示域对齐今日基线）：仅 IPC 源（CLI/委派，{@link
     * TurnOrigin#isIpcPeer()}）渲染结构化回执——本地回合的取消由 {@code stopActiveTask}
     * 的 "Stopped." 行交代，REPUBLISH 孤儿无对端调用方、回执文案无的放矢，同不渲染
     * （SILENT 亦然：其"抑制本地侧源"语义不因 cause 而放宽 IPC 判据）。RESET 一律
     * 不渲染（/new 清屏后回执属旧会话噪音）。
     */
    private void appendCancelLine(CancelCause cause, TurnOrigin origin) {
        if (cause == CancelCause.RESET || !origin.isIpcPeer()) {
            return;
        }
        String text;
        if (cause == CancelCause.TIMEOUT) {
            text = "Task cancelled: the caller's wait timed out and the turn was cancelled here.";
        } else {
            // USER_STOP 与 SILENT（对 IPC 源）共用人工终止回执文案
            text = "Task cancelled: stopped from this instance. "
                    + "Partial results (if any) have been returned to the caller.";
        }
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), text,
                    getThemeColor("Label.disabledForeground", Color.GRAY), false);
        } catch (BadLocationException e) {
            log.error("Error appending cancellation notice", e);
        }
    }

    /**
     * 面板懒创建场景领养在跑的 IPC 回合（design.md「面板创建时机」边界）。
     *
     * <p>面板是懒构造的：委派/CLI 回合可能在面板存在之前就已开跑，其回合事件无人
     * 接收——本实例会话上仍有活跃回合时，构造完成即领养之：写入活回合集合（后续
     * PROGRESS/终态照常渲染）、追加提示行 + loading + 切 Stop 模式。已错过的中途
     * 进度不补放（Q12 决策：无事件缓冲）。仅构造路径调用；invokeLater 保证等 UI
     * 字段就绪后才执行。本地回合不领养（本地回合必经本面板提交，面板先于回合存在）。
     */
    private void adoptRunningIpcTurnIfNeeded() {
        SwingUtilities.invokeLater(() -> {
            AgentLoop loop = agentLoop;
            if (loop == null) {
                return;
            }
            loop.activeTurn(InstanceContext.currentSessionKey()).ifPresent(handle -> {
                if (handle.origin().isLocalPanel()) {
                    return; // 本地回合由本面板提交（面板先于回合存在），无需领养
                }
                if (!handle.visibleToPanel()) {
                    // 不可见 IPC 命令回合：发射端不发 STARTED/PROGRESS（无显示契约），
                    // 其命令回执属对端 HTTP 信封显示域——领养写入集合会让该终态经
                    // TURN_COMPLETED 渲染进本地面板（命令回执双渲染泄漏）
                    return;
                }
                if (handle.terminalEmitted()) {
                    // 「终态已发射、句柄未摘」的死回合（emitTerminal 与 whenComplete
                    // 摘柄之间的窗口）：终态已发给当时的订阅表（事件流无缓冲），此后
                    // 再无第二个终态（tryClaimTerminal 恰好一次）来解除武装——领养即
                    // 永久 loading+Stop，必须跳过
                    return;
                }
                liveTurnIds.add(handle.id());
                try {
                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            "An IPC turn (delegation or CLI) is already running - it started "
                                    + "before this panel was opened; live activity follows.",
                            getThemeColor("Label.disabledForeground", Color.GRAY), false);
                } catch (BadLocationException e) {
                    log.error("Error adopting running IPC turn", e);
                }
                armActiveTurn();
            });
        });
    }

    /**
     * Get the appropriate AiService based on the current model selection.
     * NOTE: This method does NOT modify currentAiService to avoid side effects.
     * Uses AiServiceFactory to ensure LangSmith tracing is applied.
     */
    private AiService getAiServiceForCurrentModel() {
        String selectedModel = (String) modelSelector.getSelectedItem();
        if (selectedModel == null) {
            // For default case, use TracedAiService.wrap() for ClaudeService
            return TracedAiService.wrap(claudeService);
        }

        // 剥掉 UI 路由用的 "provider:" 前缀再进工厂：选择器条目是 provider+":"+model，
        // 连前缀进 createService 会命中另一条 cache key（spec+":"+前缀串），与构造期/
        // IpcServer 预热用裸 model 的同一逻辑服务分裂成两个实例 → switchAiService 里
        // newService != currentAiService → 重建 AgentLoop 单例、孤儿化 IPC 在跑回合。
        // 出向 API 请求不受影响：openai_compat 在 provider 内部、anthropic 在
        // createServiceForSpec 内部各自再剥一次前缀
        String modelId = selectedModel;
        int colon = selectedModel.indexOf(':');
        if (colon >= 0) {
            modelId = selectedModel.substring(colon + 1);
        }

        // Use AiServiceFactory to create the service
        // This will automatically wrap with LangSmith tracing if enabled
        AiService service = AiServiceFactory.createService(modelId);

        // Also update the raw service instance for model loading
        updateRawServiceForModel(selectedModel);

        return service;
    }

    /**
     * Update the raw service instance for model loading purposes.
     * This ensures the cached service instances (claudeService, openAiService, etc.)
     * have the correct model set for model loading operations.
     */
    private void updateRawServiceForModel(String modelId) {
        if (modelId == null) return;

        if (modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            String provider = parts[0];
            String modelName = parts[1];

            switch (provider) {
                case "openai", "deepseek", "zhipu", "moonshot", "minimax", "langcat", "ollama" -> {
                    openAiService.setModel(modelId);  // Pass full ID with prefix
                    log.info("Set {} provider model: {}", provider, modelName);
                }
                default -> {
                    claudeService.setModel(modelName);  // Anthropic API needs the bare model id (rejects a "provider:" prefix)
                    log.info("Set Anthropic provider model: {}", modelName);
                }
            }
        } else {
            // No provider prefix, assume Anthropic
            claudeService.setModel(modelId);
            log.info("Set Anthropic provider model: {}", modelId);
        }
    }

    /**
     * Switch the AI service based on the selected model.
     * This recreates the AgentLoop with the appropriate service.
     */
    private void switchAiService() {
        try {
            AiService newService = getAiServiceForCurrentModel();

            // Only recreate if service changed
            if (newService != currentAiService) {
                log.info("Switching AI service from {} to {}",
                        currentAiService.getName(), newService.getName());

                // Reset and recreate AgentLoop with new service
                AgentLoopFactory.reset();
                agentLoop = AgentLoopFactory.getAgentLoop(newService);

                if (agentLoop == null) {
                    log.warn("AgentLoop failed to initialize after service switch");
                } else {
                    // 回合事件订阅挂工厂级表（构造器 addTurnSubscriber），loop 重建后
                    // 由工厂自动重挂，此处无需再注册
                    log.info("AI service switched successfully to {}", newService.getName());
                    // Update currentAiService after successful switch
                    currentAiService = newService;
                }
            }
        } catch (Exception e) {
            log.error("Failed to switch AI service", e);
        }
    }

    /**
     * Displays a welcome message in the chat area.
     */
    private void displayWelcomeMessage() {
        log.info("Displaying welcome message");

        String welcomeMessage = "# Welcome to Gitee Ai - JMeter Agent\n\n" +
                "I'm here to help you with your JMeter test plan. You can ask me questions about JMeter, " +
                "request help with creating test elements, or get advice on optimizing your tests.\n\n" +
                "**Slash commands:**\n" +
                "- `/new` — Start a new conversation\n" +
                "- `/status` — Show agent status\n" +
                "- `/help` — Show available commands\n\n" +
                "How can I assist you today?";

        // 构造线程不保证 EDT（面板懒创建路径）；文档变更入口已加 EDT 断言（迁移期
        // 护栏），EDT 上保持同步渲染，非 EDT 自投 EDT
        Runnable append = () -> {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(), welcomeMessage, null, true);
            } catch (BadLocationException e) {
                log.error("Error displaying welcome message", e);
            }
        };
        if (EventQueue.isDispatchThread()) {
            append.run();
        } else {
            SwingUtilities.invokeLater(append);
        }
    }

    /**
     * 渲染代数 +1 并清空活回合集合：/new、"+"、关闭整合清空三处重置共用。旧会话的
     * 迟到渲染（代数快照不符）与旧回合的迟到终态（集合外）从此全部丢弃。只在 EDT 调用。
     */
    private void advanceRenderEpoch() {
        conversationGeneration++;
        liveTurnIds.clear();
    }

    /**
     * Starts a new conversation by clearing the chat area and AgentLoop session.
     */
    private void startNewConversation() {
        log.info("Starting new conversation");

        advanceRenderEpoch();

        // 重置核心（与 cmdNew 共用）：中止在跑回合与子代理、代数 +1、归档/清空/落盘。
        // 走工厂跨实例路由（对齐 Stop 的 signalCancelAny 先例）：RESET 先触达当前+
        // 退役 loop 上该会话的在跑回合，重置核心在 self（面板持有的 loop，直构亦可）
        // 上执行
        if (agentLoop != null) {
            AgentLoopFactory.resetConversationAny(agentLoop, InstanceContext.currentSessionKey());
        }

        // Clear the chat area
        chatArea.setText("");

        // Display welcome message
        displayWelcomeMessage();

        // 重置翻换代数后，垂死回合的取消终态渲染被代数过滤丢弃——无人回调复位 UI，
        // 须自行复位，否则 Stop 按钮常驻、Send 按钮停留在注入模式。
        removeLoadingIndicator();
        setButtonToSendMode();

        // A new chat is an explicit "back to the start" action: always re-pin to the bottom so
        // the welcome message is in view regardless of where the previous (now-cleared) log was
        // scrolled.
        scrollToBottom();
    }

    /**
     * Sends the message from the input field to the chat using AgentLoop.
     * 唯一入口（Enter 键 / Send 按钮 / Stop 模式下的注入 Send 都路由到此）：
     * 空串守卫 → /new 拦截 → {@link #submitToLoop}。busy 与否由 loop 的槽路由仲裁
     * （优先命令/忙期注入/开新回合三段路由），面板不再做 hasActiveRun 预路由。
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        // /new starts a fresh conversation with a clean chat area — handle it
        // specially (covers Enter key and the default Send button).
        if ("/new".equals(message)) {
            handleNewCommand();
            return;
        }

        submitToLoop(message);
    }

    /**
     * Sole local submit point: hands the message to AgentLoop; all rendering
     * (You line, loading/Stop arming, progress, final state) is driven by the
     * turn event stream. The future is left to the original caller (CLI or
     * delegated turns); the panel does not hold it.
     */
    private void submitToLoop(String message) {
        log.info("Submitting user message: {}", message);

        messageField.setText("");

        // Ensure AgentLoop is initialized
        if (agentLoop == null) {
            log.warn("AgentLoop not initialized, attempting to reinitialize");
            initializeAgentLoop();
            if (agentLoop == null) {
                try {
                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            "Agent Loop is not available. Please check your configuration.",
                            Color.RED, false);
                } catch (BadLocationException e) {
                    log.error("Error displaying error message", e);
                }
                setButtonToSendMode();
                return;
            }
        }

        agentLoop.processMessage(message, InstanceContext.currentSessionKey());
    }

    /**
     * Handle the {@code /new} command: flip the render generation, clear the chat,
     * then dispatch {@code /new} through the normal submit path. The "You: /new"
     * echo and the receipt are event-rendered: busy 期 cmdNew 同步执行（忙期注入路由 →
     * COMMAND_RESULT），空闲期经完整回合（TURN_STARTED 武装 + 终态自复位——刻意的
     * UX 差异③）。
     */
    private void handleNewCommand() {
        // /new 即重置：代数与活回合集合一并翻转（语义见 advanceRenderEpoch）。
        advanceRenderEpoch();

        // Clear the chat area for a fresh session.
        chatArea.setText("");

        submitToLoop("/new");
    }

    /**
     * 深度提炼记忆整合成功后,清空消息区并显示欢迎信息(视觉效果对齐「开启新会话」按钮)。
     * 由 {@link org.gitee.jmeter.ai.gui.CloseConsolidationDialog} 在提炼完成的 EDT 回调里调用;
     * 须在 EDT。面板未创建时 no-op。配套的数据层清空见
     * {@link org.gitee.jmeter.ai.agent.memory.CloseConsolidationCoordinator#clearCurrentSession()}。
     */
    public static void resetAfterConsolidation() {
        AiChatPanel panel = INSTANCE;
        if (panel == null) {
            return;
        }
        // 对齐 /new、"+"：代数与活回合集合一并翻转（语义见 advanceRenderEpoch）；
        // 取消路径无人回调复位，UI 须自行复位（退出取消后继续使用时不得留常驻 Stop 模式）
        panel.advanceRenderEpoch();
        panel.chatArea.setText("");
        panel.displayWelcomeMessage();
        panel.removeLoadingIndicator();
        panel.setButtonToSendMode();
    }

    /**
     * 远程 /new（CLI 直连/委派）的会话重置清屏：cmdNew 已在 loop 侧归档/清空/落盘，
     * 面板转录须随之清空并翻渲染代数（否则显示态与 session jsonl 永久分叉、旧会话
     * 迟到事件仍按旧代数放行渗入新会话）。触发点在 {@link #dispatch} 的
     * TURN_COMPLETED（空闲 Phase 3 命令回合终态）/ COMMAND_RESULT（忙期 Phase 2
     * 同步命令结果）两分支；本地 /new 不经此（handleNewCommand 先清屏再提交）。
     * 视觉口径对齐 {@link #resetAfterConsolidation}。只在 EDT（dispatch 内）调用。
     */
    private void clearTranscriptForRemoteReset() {
        advanceRenderEpoch();
        chatArea.setText("");
        displayWelcomeMessage();
        removeLoadingIndicator();
        setButtonToSendMode();
    }

    /**
     * Handle AgentLoop response callback（TURN_COMPLETED / 本地 COMMAND_RESULT 的
     * 共用收尾渲染）。只经 {@link #dispatch} 到达——入口处已做代数比对（EDT 上
     * 读写、同步路径无并发窗口），此处无需复查。
     *
     * @param turnId 回合 id（TURN_COMPLETED 路径）；COMMAND_RESULT 无句柄传 null——
     *               命令回合无 PROGRESS，永不命中渐进展示集合
     */
    private void handleAgentResponse(AgentResponse response, Long turnId) {
        // Remove the loading indicator——判据为面板视角无活回合（交叠活回合下兄弟
        // 终态不得清掉在跑回合仍在用的指示）；单指示不变式（armActiveTurn 幂等）
        // 保证 armed 位与文档指示一一对应
        if (liveTurnIds.isEmpty()) {
            removeLoadingIndicator();
        }

        if (!response.isSuccess()) {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "Error: " + response.getErrorMessage(),
                        Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
        } else {
            // Display tool call information only if not already shown progressively
            // （per-turn 判定：按本回合 id 查删渐进展示集合，兄弟回合的进度不得
            // 吞掉本回合的摘要、也不得使已渐进展示过的回合重复补显）
            if (turnId == null || !progressiveToolCallTurnIds.remove(turnId)) {
                boolean showToolCalls = org.gitee.jmeter.ai.utils.AiConfig.isChatShowToolCalls();

                if (showToolCalls && response.getToolEvents() != null && !response.getToolEvents().isEmpty()) {
                    displayToolCallInfo(response.getToolEvents());
                }
            }

            processAiResponse(response.getContent());
        }

        // Re-enable input
        messageField.setEnabled(true);
        // 仍有回合在跑（如紧随其后的 re-publish 孤儿回合、交叠的兄弟回合）时保持
        // Stop 模式，由最后一个终态复位。判据用面板视角的活回合集合：终态已到 EDT
        // 即视为本回合收尾——agentLoop.hasActiveRun 在「终态已派发、注入槽未摘」的
        // 收尾窗口内误判「仍在跑」，且漏退役 loop 上的在跑回合（换血后 Stop 仍须可见）
        if (liveTurnIds.isEmpty()) {
            setButtonToSendMode();
        }
        messageField.requestFocusInWindow();
    }

    /**
     * EDT 直渲染一条进度（无内层 invokeLater）：dispatch 已在 EDT 完成代数/活回合
     * 过滤，此处再跳一拍会让末条进度排到终态渲染之后（dispatch FIFO 先入队、内层
     * invokeLater 后入队）。事件流是唯一渲染权威（P2 4.1 已删旧 presenter 腿的
     * {@code handleProgress} 包装）。
     */
    private void handleProgressNow(ProgressUpdate update, long turnId) {
        try {
            removeLoadingIndicator();

            switch (update.getType()) {
                case THINKING -> renderThinking(update.getMessage());
                case TOOL_CALL -> {
                    progressiveToolCallTurnIds.add(turnId);
                    Object payload = update.getPayload();
                    if (payload instanceof ToolEvent event) {
                        displaySingleToolEvent(event);
                    } else {
                        renderToolHint(update.getMessage());
                    }
                }
                case ERROR -> renderError(update.getMessage());
                case INTERMEDIATE_RESPONSE -> renderIntermediateResponse(update.getMessage());
                default -> renderProgress(update.getMessage());
            }
        } catch (BadLocationException e) {
            log.error("Error displaying progress", e);
        }
    }

    /**
     * 渲染 THINKING 进度：载荷可能是纯思考，也可能携带 {@code <think>…</think>} 包裹的
     * 思考 + 标签外的正文（结构化 reasoning_content 的展示形态，或模型内嵌标签）。
     * 按段拆分渲染——思考段维持灰斜体并以 {@code <think>} 标签包裹展示（标签字面
     * 可见），标签外的正文按回复正文样式（主题色 markdown）渲染，与思考内容区分。
     */
    private void renderThinking(String text) throws BadLocationException {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (TextUtils.ThinkSegment segment : TextUtils.splitThink(text)) {
            if (segment.thinking()) {
                messageProcessor.appendStyled(chatArea.getStyledDocument(),
                        "<think>" + segment.text() + "</think>",
                        new Color(0x78, 0x78, 0x78), Font.ITALIC);
            } else {
                messageProcessor.appendMarkdown(chatArea.getStyledDocument(), segment.text(), null);
            }
        }
    }

    private void renderToolHint(String hint) throws BadLocationException {
        messageProcessor.appendStyled(chatArea.getStyledDocument(), hint.stripTrailing(),
                new Color(0x64, 0x64, 0x96), Font.BOLD);
    }

    private void renderProgress(String text) throws BadLocationException {
        messageProcessor.appendMessage(chatArea.getStyledDocument(), text, Color.GRAY, false);
    }

    private void renderError(String text) throws BadLocationException {
        messageProcessor.appendStyled(chatArea.getStyledDocument(), text.stripTrailing(), Color.RED);
    }

    private void renderIntermediateResponse(String text) throws BadLocationException {
        if (text == null || text.isEmpty()) {
            return;
        }
        appendBotResponse(text);
    }

    /** Append an AI (markdown) response block with the inline 🤖 marker (foreground inherits the themed body color). */
    private void appendBotResponse(String markdown) throws BadLocationException {
        messageProcessor.appendHtml(chatArea.getStyledDocument(), botHeaderHtml(markdown));
    }

    /**
     * Build the AI response HTML with the 🤖 marker injected INSIDE the first block element
     * (e.g. {@code <p><span>🤖 </span>...}) so the bot emoji sits inline with the first line
     * instead of on its own line above the block content.
     */
    private static String botHeaderHtml(String markdown) {
        String bot = "<span style=\"font-weight:bold;color:#0066cc\">🤖: </span>";
        String md = MarkdownParserHolder.renderToHtml(markdown);
        String injected;
        int gt = md.indexOf('>');
        if (!md.isEmpty() && md.charAt(0) == '<' && gt > 0 && gt <= 4) {
            // md starts with a short opening tag like <p> or <h1> — inject right after it
            injected = md.substring(0, gt + 1) + bot + md.substring(gt + 1);
        } else {
            injected = bot + md;
        }
        return "<div>" + injected + "</div>";
    }

    /**
     * Display tool call information in the chat area (fallback for non-progressive mode).
     */
    private void displayToolCallInfo(List<ToolEvent> toolEvents) {
        try {
            for (ToolEvent event : toolEvents) {
                displaySingleToolEvent(event);
            }
        } catch (BadLocationException e) {
            log.error("Error displaying tool call info", e);
        }
    }

    /**
     * Display a single tool event with styled output.
     */
    private void displaySingleToolEvent(ToolEvent event) throws BadLocationException {
        int maxToolResultLength = org.gitee.jmeter.ai.utils.AiConfig.getChatToolResultMaxLength();

        Color statusColor;
        String statusIcon;
        switch (event.getStatus()) {
            case OK -> {
                statusColor = new Color(34, 139, 34);
                statusIcon = "✓";
            }
            case ERROR -> {
                statusColor = new Color(220, 20, 60);
                statusIcon = "✗";
            }
            case TIMEOUT -> {
                statusColor = new Color(255, 140, 0);
                statusIcon = "⏱";
            }
            case NOT_FOUND -> {
                statusColor = new Color(128, 128, 128);
                statusIcon = "?";
            }
            default -> {
                statusColor = Color.BLACK;
                statusIcon = "-";
            }
        }

        StringBuilder sb = new StringBuilder("<div>");
        sb.append("<span style=\"font-weight:bold;color:#646496\">🔧</span> ");
        sb.append("<span style=\"color:").append(UiThemeUtil.toHex(statusColor)).append("\">");
        sb.append(MessageProcessor.escapeHtml(statusIcon + " " + event.getToolName() + " [" + event.getDurationMs() + "ms]"));
        sb.append("</span>");

        if (event.getArguments() != null && !event.getArguments().isEmpty()) {
            String argsStr = formatArguments(event.getArguments());
            String displayArgs = argsStr.stripTrailing();
            if (argsStr.length() > maxToolResultLength) {
                displayArgs = argsStr.substring(0, maxToolResultLength) + "...(truncated, total " + argsStr.length() + " chars)";
            }
            sb.append("<br><span style=\"color:#4682b4;font-style:italic\">Args: ")
              .append(MessageProcessor.escapeHtml(displayArgs)).append("</span>");
        }

        String detail = event.getDetail();
        if (detail != null && !detail.isEmpty()) {
            String displayDetail = detail.stripTrailing();
            if (detail.length() > maxToolResultLength) {
                displayDetail = detail.substring(0, maxToolResultLength) + "...(truncated, total " + detail.length() + " chars)";
            }
            sb.append("<br><span style=\"color:#646464;font-style:italic\">Result: ")
              .append(MessageProcessor.escapeHtml(displayDetail)).append("</span>");
        }
        sb.append("</div>");

        messageProcessor.appendHtml(chatArea.getStyledDocument(), sb.toString());
    }

    /**
     * Format arguments map to a readable string.
     */
    private String formatArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        return arguments.toString();
    }

    /**
     * Stop the active AI task, triggered by the Stop button.
     *
     * <p>signalCancelAny（非阻塞：置 abort / interrupt / cancel future / 摘注入路由槽，
     * USER_STOP）保留在 EDT 同步执行——经工厂路由当前 + 退役 loop（模型切换换血后，
     * 在跑回合可能还挂在旧 loop 上，直发 agentLoop 会漏）。维持「UI 复位 ⇒ 路由槽
     * 必已摘除」不变式：STOP 后立即输入走正常发送，而非被注入垂死回合遭静默作废。
     * 垂死回合的收尾等待（≤5s）在后台线程。取消后的复位无条件执行（不依赖
     * TURN_CANCELLED 事件）：回合已终、终态事件仍在 EDT 队列未出队的毫秒窗口内点击
     * Stop 不死寂。
     */
    private void stopActiveTask() {
        if (agentLoop != null) {
            final String sessionKey = InstanceContext.currentSessionKey();
            AgentLoopFactory.signalCancelAny(sessionKey);
            CompletableFuture.runAsync(
                    () -> agentLoop.waitForCancellation(sessionKey, 5, TimeUnit.SECONDS));
        }
        removeLoadingIndicator();
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(),
                    "Stopped.", getThemeColor("Label.disabledForeground", Color.GRAY), false);
        } catch (BadLocationException e) {
            log.error("Error displaying stop message", e);
        }
        setButtonToSendMode();
        messageField.requestFocusInWindow();
    }

    private void setButtonToStopMode() {
        // Show the separate stop button
        stopButton.setVisible(true);

        // Send 按钮忙时改显 Insert——点击效果是把新消息插入当前运行回合；
        sendButton.setText("Insert");
        sendButton.setToolTipText("Insert the message into the running AI task");
    }

    private void setButtonToSendMode() {
        // Hide the stop button
        stopButton.setVisible(false);

        // Reset send button to normal behavior
        sendButton.setText("Send");
        sendButton.setToolTipText(null);
        sendButton.setForeground(null);
        sendButton.setBackground(null);
        sendButton.setBorder(UIManager.getBorder("Button.border"));
        sendButton.setEnabled(true);
        for (ActionListener al : sendButton.getActionListeners()) {
            sendButton.removeActionListener(al);
        }
        sendButton.addActionListener(e -> sendMessage());
    }

    /**
     * Removes the loading indicator from the chat area. 武装位未置时 no-op——指示必不在
     * 文档里，跳过下层全文档扫描；置位时移除（或确认 miss）后清零。
     */
    private void removeLoadingIndicator() {
        if (!loadingIndicatorArmed) {
            return;
        }
        try {
            messageProcessor.removeLoadingIndicator(chatArea.getStyledDocument());
            loadingIndicatorArmed = false;
        } catch (BadLocationException e) {
            log.error("Error removing loading indicator", e);
        }
    }

    /**
     * Whether the chat viewport is pinned to the bottom (within ~one line of the maximum).
     * Used as the smart-scroll gate: auto-scroll follows new content only while the user is at
     * the tail; scrolling up (by any means — drag, wheel, button, keyboard) leaves the view in
     * place, and scrolling back to the bottom re-enables following. The tolerance is the
     * scrollbar's unit increment (about one text line) so it adapts to font size / DPI instead
     * of a brittle fixed pixel count.
     */
    private boolean isChatAtBottom() {
        JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
        int tolerance = vertical.getUnitIncrement();
        if (tolerance <= 0) {
            tolerance = 16;
        }
        return vertical.getValue() + vertical.getVisibleAmount() >= vertical.getMaximum() - tolerance;
    }

    /**
     * Scroll the chat viewport to the very bottom. Invoked on the EDT via {@code invokeLater} so
     * it runs after the document layout pass has updated the scrollbar's maximum for the just
     * appended content. {@code setValue(max)} is clamped by the model to {@code max - extent}
     * (the true bottom) since the extent (viewport height) is stable.
     */
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    /**
     * Processes an AI response and displays it in the chat area.
     * 
     * @param response The AI response to process
     */
    private void processAiResponse(String response) {
        if (response == null || response.isEmpty()) {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "No response from AI. Please try again.", Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
            log.warn("Empty AI response");
            return;
        }

        log.info("Processing AI response: {}", response.substring(0, Math.min(100, response.length())));

        // Add the AI response to the chat
        log.info("Appending AI response to chat");
        try {
            // AI response header + markdown content as one HTML block
            appendBotResponse(response);
        } catch (BadLocationException e) {
            log.error("Error appending AI response to chat", e);
        }
        // Scrolling is handled inside appendHtml (smart auto-scroll: only when pinned to bottom).
    }

    /**
     * Cleans up resources when the panel is no longer needed.
     */
    public void cleanup() {
        // Unregister property change listener
        UIManager.removePropertyChangeListener(this);

        // 摘工厂级回合事件订阅 + 单实例注册：面板销毁后不再接收回合事件，
        // 静态引用不再钉住本面板（防泄漏与幽灵渲染）。
        AgentLoopFactory.removeTurnSubscriber(this);
        INSTANCE = null;

        // Detach from SelectionTracker (other consumers may still be subscribed,
        // so we don't call SelectionTracker.uninstall()).
        if (selectionTrackerListener != null) {
            SelectionTracker.removeListener(selectionTrackerListener);
            selectionTrackerListener = null;
        }
    }

    /**
     * Apply the current JMeter theme to the chat area: the component background (the chat's
     * base color) and the HTML StyleSheet rules (themed foreground + code/table backgrounds).
     * Called once during construction and again whenever the Look and Feel changes
     * (see {@link #propertyChange}).
     *
     * <p>The body rule intentionally omits a CSS {@code background}; the JTextPane component
     * background fills the viewport and repaint applies it instantly on theme switch (no HTML
     * view re-parse needed, which Swing would otherwise cache). {@code JViewport} inherits the
     * child component background, so the scroll pane area stays consistent without extra setup.
     */
    private void applyChatTheme() {
        Color bg = getThemeColor("TextPane.background", getThemeColor("Panel.background", Color.WHITE));
        chatArea.setOpaque(true);
        chatArea.setBackground(bg);

        Font font = chatArea.getFont();
        int fontPt = font.getSize();
        Color textFg = getThemeColor("TextPane.foreground", Color.BLACK);
        Color codeBg = UiThemeUtil.getCodeBlockBackground();
        StyleSheet ss = ((HTMLEditorKit) chatArea.getEditorKit()).getStyleSheet();
        ss.addRule("body { font-family:" + font.getFamily() + "; font-size:" + fontPt
                + "pt; color:" + UiThemeUtil.toHex(textFg) + "; }");
        ss.addRule("p { margin:5px 0; }");
        ss.addRule("div { margin:5px 0; }");
        // Headings scale with the base font size (browser-standard ratios) so they follow
        // ai.chat.font.size instead of Swing's built-in fixed heading sizes.
        ss.addRule("h1 { font-size:" + Math.round(fontPt * 1.50f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h2 { font-size:" + Math.round(fontPt * 1.30f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h3 { font-size:" + Math.round(fontPt * 1.17f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h4 { font-size:" + fontPt + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h5 { font-size:" + Math.round(fontPt * 0.83f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h6 { font-size:" + Math.round(fontPt * 0.67f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("ul,ol { margin:4px 0; padding-left:22px; }");
        ss.addRule("li { margin:1px 0; }");
        // font-size is required here: once font-family is set, Swing's CSS engine no longer
        // inherits the body font size and would fall back to a default — the same applies
        // to any rule that specifies a font-family.
        ss.addRule("pre, code, kbd, samp { font-family: Monospaced; font-size:" + fontPt + "pt; }");
        // Inline code/kbd/samp: themed background + padding so they read as distinct "code chips"
        // instead of bare monospaced text. Background reuses codeBg (theme-aware, guaranteed
        // contrast vs the panel); font stays at fontPt so it scales with ai.chat.font.size.
        ss.addRule("code, kbd, samp { background:" + UiThemeUtil.toHex(codeBg) + "; color:"
                + UiThemeUtil.toHex(textFg) + "; padding:1px 3px; }");
        ss.addRule("pre { background:" + UiThemeUtil.toHex(codeBg) + "; padding:4px 6px; margin:4px 0; }");
        // Inside <pre><code>, drop the inline "chip" so the code block stays one solid panel.
        // Harmless even if Swing ignores the descendant selector: both backgrounds are codeBg.
        ss.addRule("pre code { background: transparent; padding:0; }");
        ss.addRule("table { border-collapse:collapse; margin:4px 0; }");
        ss.addRule("th, td { border:1px solid #999; padding:2px 6px; }");
        ss.addRule("th { background:" + UiThemeUtil.toHex(codeBg) + "; }");
        ss.addRule("blockquote { border-left:3px solid #bbb; margin:4px 0; padding-left:8px; color:#666; }");

        chatArea.repaint();
    }

    /**
     * Updates the font sizes of chat components based on JMeter's current scale
     * factor
     */
    private void updateFontSizes() {
        float scale = JMeterUIDefaults.INSTANCE.getScale();

        // Update chat area font
        Font currentChatFont = chatArea.getFont();
        float newChatSize = baseChatFontSize * scale;
        Font newChatFont = currentChatFont.deriveFont(newChatSize);
        chatArea.setFont(newChatFont);
        messageProcessor.setBaseFont(newChatFont);

        // Update message field font
        Font currentMessageFont = messageField.getFont();
        float newMessageSize = baseMessageFontSize * scale;
        Font newMessageFont = currentMessageFont.deriveFont(newMessageSize);
        messageField.setFont(newMessageFont);
    }

    /**
     * Handles property change events, specifically for UI refresh events triggered
     * by zoom actions
     */
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        // Check if this is a UI refresh event
        if ("lookAndFeel".equals(evt.getPropertyName())) {
            // Update font sizes based on the current scale
            updateFontSizes();
            // Re-apply themed background + StyleSheet so the chat follows the new Look and Feel
            applyChatTheme();
        }
    }

    /**
     * Gets a color from the current UIManager theme, falling back to a default if not available.
     *
     * @param key The UIManager color key
     * @param fallback The fallback color if the key is not found
     * @return The theme color or the fallback
     */
    private static Color getThemeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }
}