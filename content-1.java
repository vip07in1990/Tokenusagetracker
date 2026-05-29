import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import okhttp3.*;
import com.google.gson.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Vector;

/**
 * A simple Swing application that lets you send chat completion requests to
 * GitHub Models and records token usage for each request. It also has a
 * method to fetch aggregated Copilot usage reports from the enterprise
 * metrics endpoints.
 *
 * To run this example you need the OkHttp and Gson libraries on your
 * classpath. It assumes you have a fine‑grained personal access token
 * with `models:read` permission for inference calls and
 * `manage_billing:copilot` or `read:enterprise` permissions for the
 * metrics endpoints. Set these tokens as environment variables before
 * running the application (see TOKEN_USAGE_TRACKER and METRICS_TOKEN below).
 */
public class TokenUsageTracker extends JFrame {
    private static final String MODELS_URL = "https://models.github.ai/inference/chat/completions";
    // enterprise metrics endpoints (replace {enterprise} with your org slug)
    private static final String METRICS_DAY_URL =
            "https://api.github.com/enterprises/{enterprise}/copilot/metrics/reports/enterprise-1-day";
    private static final String METRICS_28DAY_URL =
            "https://api.github.com/enterprises/{enterprise}/copilot/metrics/reports/enterprise-28-day/latest";

    private final JTextArea promptArea;
    private final JTextField modelField;
    private final JTable usageTable;
    private final DefaultTableModel tableModel;
    private final OkHttpClient client;
    private final String inferenceToken;
    private final String metricsToken;

    public TokenUsageTracker() {
        super("GitHub Models Token Usage Tracker");
        this.inferenceToken = System.getenv().getOrDefault("TOKEN_USAGE_TRACKER", "");
        this.metricsToken = System.getenv().getOrDefault("METRICS_TOKEN", "");
        this.client = new OkHttpClient();

        // UI components
        promptArea = new JTextArea(5, 40);
        modelField = new JTextField("openai/gpt-4.1", 20);
        JButton sendButton = new JButton("Send");
        JButton reportButton = new JButton("Fetch 1‑Day Report");

        tableModel = new DefaultTableModel(new Object[]{"Timestamp", "Model", "Prompt", "Tokens"}, 0);
        usageTable = new JTable(tableModel);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(new JLabel("Model:"), BorderLayout.WEST);
        topPanel.add(modelField, BorderLayout.CENTER);
        topPanel.add(sendButton, BorderLayout.EAST);

        JPanel promptPanel = new JPanel(new BorderLayout());
        promptPanel.setBorder(BorderFactory.createTitledBorder("Prompt"));
        promptPanel.add(new JScrollPane(promptArea), BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(BorderFactory.createTitledBorder("Usage history"));
        bottomPanel.add(new JScrollPane(usageTable), BorderLayout.CENTER);
        bottomPanel.add(reportButton, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);
        add(promptPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // button actions
        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendInferenceRequest();
            }
        });

        reportButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String day = LocalDate.now().toString();
                fetchEnterpriseReport(day);
            }
        });

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
    }

    private void sendInferenceRequest() {
        String modelId = modelField.getText().trim();
        String prompt = promptArea.getText().trim();
        if (prompt.isEmpty() || modelId.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a model and prompt.");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelId);
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);
        payload.add("messages", messages);

        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(MODELS_URL)
                .addHeader("Authorization", "Bearer " + inferenceToken)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        JOptionPane.showMessageDialog(TokenUsageTracker.this,
                                "Inference call failed: " + response.code());
                        return null;
                    }
                    String json = response.body().string();
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    JsonArray choices = root.getAsJsonArray("choices");
                    String assistantReply = "";
                    if (choices != null && choices.size() > 0) {
                        JsonObject first = choices.get(0).getAsJsonObject();
                        JsonObject msg = first.getAsJsonObject("message");
                        assistantReply = msg.get("content").getAsString();
                    }
                    JsonObject usage = root.getAsJsonObject("usage");
                    int totalTokens = usage != null && usage.has("total_tokens") ? usage.get("total_tokens").getAsInt() : 0;
                    // update UI
                    final String reply = assistantReply;
                    SwingUtilities.invokeLater(() -> {
                        tableModel.addRow(new Object[]{
                                java.time.LocalTime.now().toString(),
                                modelId,
                                prompt,
                                totalTokens
                        });
                        JOptionPane.showMessageDialog(TokenUsageTracker.this,
                                "Assistant replied:\n" + reply + "\n\nTokens used: " + totalTokens);
                    });
                } catch (IOException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(TokenUsageTracker.this, "Error: " + ex.getMessage());
                }
                return null;
            }
        };
        worker.execute();
    }

    private void fetchEnterpriseReport(String day) {
        String enterpriseSlug = System.getenv().getOrDefault("ENTERPRISE_SLUG", "your-enterprise");
        String url = METRICS_DAY_URL.replace("{enterprise}", enterpriseSlug) + "?day=" + day;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("Authorization", "Bearer " + metricsToken)
                .addHeader("X-GitHub-Api-Version", "2026-03-10")
                .get()
                .build();

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        JOptionPane.showMessageDialog(TokenUsageTracker.this,
                                "Metrics call failed: " + response.code());
                        return null;
                    }
                    String json = response.body().string();
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    JsonArray links = root.getAsJsonArray("download_links");
                    if (links != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < links.size(); i++) {
                            sb.append(links.get(i).getAsString()).append("\n");
                        }
                        JOptionPane.showMessageDialog(TokenUsageTracker.this,
                                "Download links for usage report:\n" + sb);
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(TokenUsageTracker.this, "Error: " + ex.getMessage());
                }
                return null;
            }
        };
        worker.execute();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TokenUsageTracker().setVisible(true));
    }
}
