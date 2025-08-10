import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import java.util.stream.Collectors;


public class ChatbotApp {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatFrame().setVisible(true));
    }
}

/* ---------------- Chatbot core ---------------- */
class ChatbotCore {
    private final List<String> faqQuestions = new ArrayList<>();
    private final List<String> faqAnswers = new ArrayList<>();
    private final Set<String> stopwords;
    private final Map<Pattern, String> rules = new LinkedHashMap<>();
    private final Path faqFile = Paths.get("faqs.txt");

    public ChatbotCore() {
        // minimal stopwords
        stopwords = new HashSet<>(Arrays.asList(
                "a","an","the","is","are","was","were","in","on","at","to","for","of","and","or","not","be","do","does","did","how","what","when","where","which","that","i","you"
        ));

        // rules (greetings, thanks, bye, help)
        rules.put(Pattern.compile("^(hi|hello|hey|good morning|good afternoon|good evening)\\b.*", Pattern.CASE_INSENSITIVE),
                "Hello! How can I help you today?");
        rules.put(Pattern.compile(".*\\b(thanks|thank you|thx)\\b.*", Pattern.CASE_INSENSITIVE),
                "You're welcome — happy to help!");
        rules.put(Pattern.compile("^(bye|goodbye|see ya|exit)\\b.*", Pattern.CASE_INSENSITIVE),
                "Goodbye! If you need anything else, just start a new chat.");
        rules.put(Pattern.compile(".*\\b(help|support)\\b.*", Pattern.CASE_INSENSITIVE),
                "I can answer FAQs or you can teach me new Q→A pairs using: train:question|answer");

        // load persisted FAQs if present, otherwise seed defaults
        if (!loadFAQs()) seedFAQs();
    }

    private void seedFAQs() {
        addFAQ("what can you do", "I can answer frequently asked questions. You may also teach me new Q->A pairs using: train:question|answer");
        addFAQ("how do i train you", "Type: train:Your question?|Your answer. Example: train:What is your name?|I am DemoBot.");
        addFAQ("how do i clear chat", "Use the Clear button in the UI to clear the conversation pane.");
        addFAQ("what languages do you support", "This demo uses simple English-only preprocessing. You can expand it later.");
    }

    public synchronized String trainFromString(String raw) {
        String payload = raw.substring("train:".length()).trim();
        int sep = payload.indexOf('|');
        if (sep == -1) return "Training failed. Use format: train:question|answer";
        String q = payload.substring(0, sep).trim();
        String a = payload.substring(sep + 1).trim();
        if (q.isEmpty() || a.isEmpty()) return "Training failed. Question or answer empty.";
        addFAQ(q, a);
        saveFAQs(); // persist immediately
        return "Thanks — I learned a new response for: \"" + q + "\"";
    }

    // add FAQ (stores normalized question internally)
    public synchronized void addFAQ(String questionRaw, String answer) {
        String q = normalize(questionRaw);
        faqQuestions.add(q);
        faqAnswers.add(answer);
    }

    public synchronized Response answer(String user) {
        String trimmed = user.trim();
        if (trimmed.isEmpty()) return new Response("Please type something.", 0.0);

        // rules
        for (Map.Entry<Pattern, String> e : rules.entrySet()) {
            if (e.getKey().matcher(trimmed).matches()) return new Response(e.getValue(), 1.0);
        }

        // training command
        if (trimmed.toLowerCase().startsWith("train:")) {
            String resp = trainFromString(trimmed);
            return new Response(resp, 1.0);
        }

        // similarity matching
        String qnorm = normalize(trimmed);
        Map<String,Integer> qvec = tf(qnorm);

        double best = -1.0;
        int bestIdx = -1;
        for (int i = 0; i < faqQuestions.size(); i++) {
            Map<String,Integer> svec = tf(faqQuestions.get(i));
            double sim = cosine(qvec, svec);
            if (sim > best) { best = sim; bestIdx = i; }
        }

        double threshold = 0.30; // confidence threshold
        if (bestIdx != -1 && best >= threshold) {
            return new Response(faqAnswers.get(bestIdx), best);
        }

        String fallback = "Sorry, I don't know the answer to that. " +
                "You can teach me using:\ntrain:Your question?|Your answer";
        return new Response(fallback, best < 0 ? 0.0 : best);
    }

    /* ---------- helpers ---------- */
    private String normalize(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        List<String> toks = Arrays.stream(cleaned.split(" "))
                .filter(tok -> !tok.isEmpty() && !stopwords.contains(tok))
                .collect(Collectors.toList());
        return String.join(" ", toks);
    }

    private Map<String,Integer> tf(String text) {
        Map<String,Integer> m = new HashMap<>();
        if (text == null || text.isEmpty()) return m;
        for (String t : text.split("\\s+")) {
            if (t.isEmpty()) continue;
            m.put(t, m.getOrDefault(t, 0) + 1);
        }
        return m;
    }

    private double cosine(Map<String,Integer> a, Map<String,Integer> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        double dot = 0;
        for (Map.Entry<String,Integer> e : a.entrySet()) dot += e.getValue() * b.getOrDefault(e.getKey(), 0);
        double na = 0, nb = 0;
        for (int v : a.values()) na += v*v;
        for (int v : b.values()) nb += v*v;
        na = Math.sqrt(na); nb = Math.sqrt(nb);
        if (na == 0 || nb == 0) return 0.0;
        return dot / (na*nb);
    }

    /* ---------- persistence (simple) ---------- */
    private boolean loadFAQs() {
        if (!Files.exists(faqFile)) return false;
        try {
            List<String> lines = Files.readAllLines(faqFile);
            faqQuestions.clear();
            faqAnswers.clear();
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                // store as rawQuestion|||answer (we saved normalized question earlier)
                int sep = line.indexOf("|||");
                if (sep == -1) continue;
                String q = line.substring(0, sep);
                String a = line.substring(sep + 3);
                faqQuestions.add(q);
                faqAnswers.add(a);
            }
            return !faqQuestions.isEmpty();
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    private void saveFAQs() {
        try (BufferedWriter bw = Files.newBufferedWriter(faqFile)) {
            for (int i = 0; i < faqQuestions.size(); i++) {
                bw.write(faqQuestions.get(i) + "|||" + faqAnswers.get(i));
                bw.newLine();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}

/* Response class */
class Response {
    final String text;
    final double confidence; // similarity or 1.0 for rule/training

    Response(String t, double c) { text = t; confidence = c; }
}

/* ---------------- GUI ---------------- */
class ChatFrame extends JFrame {
    private final ChatbotCore bot = new ChatbotCore();

    private final JTextPane chatPane = new JTextPane();
    private final JTextField inputField = new JTextField();
    private final JButton sendBtn = new JButton("Send");
    private final JButton trainBtn = new JButton("Train");
    private final JButton clearBtn = new JButton("Clear");

    ChatFrame() {
        setTitle("Chatbot — NLP Demo");
        setSize(800, 560);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(8,8));

        // Chat area
        chatPane.setEditable(false);
        chatPane.setFont(new Font("SansSerif", Font.PLAIN, 14));
        JScrollPane scroll = new JScrollPane(chatPane);
        scroll.setBorder(BorderFactory.createTitledBorder("Conversation"));
        add(scroll, BorderLayout.CENTER);

        // Input + buttons
        JPanel bottom = new JPanel(new BorderLayout(6,6));
        bottom.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
        inputField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        bottom.add(inputField, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.add(trainBtn);
        btns.add(clearBtn);
        btns.add(sendBtn);
        bottom.add(btns, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        // Left helper panel
        JPanel left = new JPanel(new BorderLayout());
        left.setPreferredSize(new Dimension(240, 0));
        left.setBorder(BorderFactory.createTitledBorder("Tips & Examples"));
        JTextArea tips = new JTextArea();
        tips.setEditable(false);
        tips.setLineWrap(true);
        tips.setWrapStyleWord(true);
        tips.setText(
                "Try these sample inputs:\n" +
                "- hello\n" +
                "- what can you do\n" +
                "- how do i train you\n\n" +
                "TRAINING:\n" +
                "Type training inline: train:How do I reset my password?|You can reset from Settings -> Account -> Reset Password.\n\n" +
                "Use 'Clear' to clear the conversation."
        );
        tips.setFont(new Font("SansSerif", Font.PLAIN, 13));
        left.add(new JScrollPane(tips), BorderLayout.CENTER);
        add(left, BorderLayout.WEST);

        // actions
        sendBtn.addActionListener(e -> handleSend());
        inputField.addActionListener(e -> handleSend());
        trainBtn.addActionListener(e -> showTrainDialog());
        clearBtn.addActionListener(e -> chatPane.setText(""));

        // starter message
        appendBot("Hi — I'm a demo chatbot. Ask me something or teach me using 'train:question|answer'.");
    }

    private void handleSend() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        appendUser(text);
        inputField.setText("");

        // get response
        SwingUtilities.invokeLater(() -> {
            Response r = bot.answer(text);
            String conf = r.confidence > 0 && r.confidence < 1.0 ? String.format(" (confidence: %.2f)", r.confidence) : "";
            appendBot(r.text + conf);
        });
    }

    private void showTrainDialog() {
        String example = "train:What is your name?|I am DemoBot.";
        String input = JOptionPane.showInputDialog(this, "Enter training (format: train:question|answer):", example);
        if (input == null || input.trim().isEmpty()) return;
        appendUser(input.trim());
        Response r = bot.answer(input.trim());
        appendBot(r.text);
    }

    /* ---- helpers for styled chat ---- */
    private void appendUser(String s) {
        appendStyled("You: ", Color.BLUE, true);
        appendStyled(s + "\n\n", Color.BLACK, false);
    }
    private void appendBot(String s) {
        appendStyled("Bot: ", new Color(0,120,0), true);
        appendStyled(s + "\n\n", Color.DARK_GRAY, false);
    }
    private void appendStyled(String text, Color color, boolean bold) {
        StyledDocument doc = chatPane.getStyledDocument();
        Style style = chatPane.addStyle("s", null);
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, bold);
        try {
            doc.insertString(doc.getLength(), text, style);
            chatPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) { e.printStackTrace(); }
    }
}