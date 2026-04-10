package com.tafj.reverse.gui;

import com.tafj.reverse.translator.TafjTranslator;
import com.tafj.reverse.model.TranslationResult;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TafjReverseGUI {

    private VirtualizedScrollPane<CodeArea> javaCodeScrollPane;
    private VirtualizedScrollPane<CodeArea> jbcCodeScrollPane;
    private CodeArea javaCodeArea;
    private CodeArea jbcCodeArea;
    private Label statusLabel;
    private TafjTranslator translator;
    private TreeView<String> jarTreeView;
    private Map<String, byte[]> jarEntries = new HashMap<>();
    /**
     * Custom TreeItem that holds the JAR entry name
     */
    private static class JarEntryTreeItem extends TreeItem<String> {
        private final String entryName;
        
        public JarEntryTreeItem(String displayName, String entryName) {
            super(displayName);
            this.entryName = entryName;
        }
        
        public String getEntryName() {
            return entryName;
        }
    }

    private static final Pattern KEYWORDS = Pattern.compile(
        "\\b(SUBROUTINE|GOSUB|RETURN|IF|THEN|ELSE|END|FOR|NEXT|BEGIN|CASE|DO|WHILE|BREAK|CONTINUE|" +
        "PRINT|READ|READU|WRITE|OPEN|CLOSE|DIM|MAT|EQUATE|ON|GOTO|STOP|ABORT|DEBUG|NULL|MATCHES|" +
        "FMT|FIELD|DCOUNT|ABS|LEN|TRIM|TRIMR|TRIML|UPCASE|LCASE|DATE|TIME|STATUS|SYSTEM|" +
        "CRT|DELETE|SELECT|READNEXT|READPREV|CLEAR|RELEASE|UNLOCK|LOCK|MATREAD|" +
        "MATWRITE|MATREADU|MATWRITEU|COPY|MOVE|SWAP|INSERT|DELETE|REPLACE|SPLIT|JOIN|" +
        "FIELD|CHANGE|COUNT|FIND|FINDSTR|LOCATE|CALLJ|CALLJEE|" +
        "SEARCH|SORT|ENCRYPT|DECRYPT|USING|" +
        "OCONV|NULL|NONE|ICONV|LOOP)\\b"
    );

    public void start(Stage primaryStage) {
        translator = new TafjTranslator();

        // Create UI components
        javaCodeArea = new CodeArea();
        setupCodeArea(javaCodeArea, false);
        
        jbcCodeArea = new CodeArea();
        setupCodeArea(jbcCodeArea, true);
        
        // Wrap code areas in scroll panes
        javaCodeScrollPane = wrapInScrollPane(javaCodeArea);
        jbcCodeScrollPane = wrapInScrollPane(jbcCodeArea);
        
        // Create JAR tree view
        jarTreeView = createJarTreeView();

        // Split pane for tree and code
        SplitPane leftSplit = new SplitPane(jarTreeView, javaCodeScrollPane);
        leftSplit.setDividerPositions(0.3);
        
        // Split pane for Java and JBC code
        SplitPane mainSplit = new SplitPane(leftSplit, jbcCodeScrollPane);
        mainSplit.setDividerPositions(0.5, 0.75);

        // Menu Bar
        MenuBar menuBar = createMenuBar(primaryStage);

        // Status Bar
        statusLabel = new Label("Ready - Load a .class, .java, or .jar file");
        statusLabel.setStyle("-fx-padding: 5px; -fx-background-color: #f0f0f0;");

        // Main Layout
        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setCenter(mainSplit);
        root.setBottom(statusLabel);

        // Scene
        Scene scene = new Scene(root, 1000, 700);
        // Add stylesheet if it exists
        var cssResource = getClass().getResource("/styles.css");
        if (cssResource != null) {
            scene.getStylesheets().add(cssResource.toExternalForm());
        }

        primaryStage.setTitle("TAFJ Reverse Translation Tool v1.0");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void setupCodeArea(CodeArea codeArea, boolean isJbc) {
        codeArea.setEditable(false);
        
        // Disable word wrap to enable horizontal scrolling
        codeArea.setWrapText(false);

        // Add line numbers
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));

        // Add syntax highlighting on text change (only for JBC code area)
        if (isJbc) {
            codeArea.textProperty().addListener((obs, oldText, newText) -> {
                codeArea.setStyleSpans(0, computeHighlighting(newText));
            });
        }
    }
    
    private VirtualizedScrollPane<CodeArea> wrapInScrollPane(CodeArea codeArea) {
        VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(codeArea);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scrollPane;
    }

    private TreeView<String> createJarTreeView() {
        TreeView<String> treeView = new TreeView<>();
        TreeItem<String> root = new TreeItem<>("JAR Contents");
        root.setExpanded(true);
        treeView.setRoot(root);
        treeView.setShowRoot(true);
        
        // Handle tree item selection
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null && newSelection instanceof JarEntryTreeItem) {
                JarEntryTreeItem item = (JarEntryTreeItem) newSelection;
                String entryName = item.getEntryName();
                if (entryName != null) {
                    loadJarEntry(entryName);
                }
            }
        });
        
        return treeView;
    }
    
    private void loadJarFile(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            jarEntries.clear();
            jarFile.getAbsolutePath();
            
            TreeItem<String> root = jarTreeView.getRoot();
            root.getChildren().clear();
            
            // Create package structure
            Map<String, TreeItem<String>> packageMap = new TreeMap<>();
            
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                
                // Skip non-class files and directories
                if (entry.isDirectory() || !name.endsWith(".class")) {
                    continue;
                }
                
                // Read class file bytes
                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] data = is.readAllBytes();
                    jarEntries.put(name, data);
                }
                
                // Create tree structure based on package
                String[] parts = name.replace(".class", "").split("/");
                String className = parts[parts.length - 1];
                
                TreeItem<String> parent = root;
                StringBuilder pathBuilder = new StringBuilder();
                
                // Create package folders
                for (int i = 0; i < parts.length - 1; i++) {
                    pathBuilder.append(parts[i]).append("/");
                    String path = pathBuilder.toString();
                    
                    if (!packageMap.containsKey(path)) {
                        TreeItem<String> folder = new TreeItem<>(parts[i]);
                        folder.setExpanded(true);
                        parent.getChildren().add(folder);
                        packageMap.put(path, folder);
                    }
                    parent = packageMap.get(path);
                }
                
                // Add class file using custom TreeItem
                JarEntryTreeItem classItem = new JarEntryTreeItem(className + ".class", name);
                parent.getChildren().add(classItem);
            }
            
            statusLabel.setText("Loaded JAR: " + jarFile.getName() + " (" + jarEntries.size() + " classes)");
            
        } catch (IOException e) {
            showError("Error loading JAR: " + e.getMessage());
            statusLabel.setText("Error loading JAR");
        }
    }
    
    private void loadJarEntry(String entryName) {
        try {
            byte[] data = jarEntries.get(entryName);
            if (data == null) {
                return;
            }
            
            // Create temp file for decompilation
            File tempClass = File.createTempFile("decompile", ".class");
            tempClass.deleteOnExit();
            
            try (FileOutputStream fos = new FileOutputStream(tempClass)) {
                fos.write(data);
            }
            
            // Decompile class file
            String javaSource = translator.decompileClass(tempClass);
            
            // Display Java code
            javaCodeArea.clear();
            javaCodeArea.appendText(javaSource);
            
            // Translate to JBC
            TranslationResult result = translator.translateToJBC(javaSource);
            jbcCodeArea.clear();
            jbcCodeArea.appendText(result.getJbcCode());
            
            statusLabel.setText("Viewing: " + entryName);
            
        } catch (Exception e) {
            showError("Error loading class: " + e.getMessage());
        }
    }

    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        Matcher matcher = KEYWORDS.matcher(text);
        int lastEnd = 0;
        
        while (matcher.find()) {
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastEnd);
            spansBuilder.add(Collections.singleton("keyword"), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastEnd);
        
        return spansBuilder.create();
    }

    private MenuBar createMenuBar(Stage primaryStage) {
        MenuBar menuBar = new MenuBar();

        // File Menu
        Menu fileMenu = new Menu("File");

        MenuItem openItem = new MenuItem("Open File...");
        openItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+O"));
        openItem.setOnAction(e -> openFile(primaryStage));
        
        MenuItem openJarItem = new MenuItem("Open JAR File...");
        openJarItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+J"));
        openJarItem.setOnAction(e -> openJarFile(primaryStage));

        MenuItem exportItem = new MenuItem("Export JBC...");
        exportItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+E"));
        exportItem.setOnAction(e -> exportJBC());

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+Q"));
        exitItem.setOnAction(e -> System.exit(0));

        fileMenu.getItems().addAll(openItem, openJarItem, new SeparatorMenuItem(), exitItem);

        // View Menu
        Menu viewMenu = new Menu("View");
        
        CheckMenuItem wrapItem = new CheckMenuItem("Word Wrap");
        wrapItem.setSelected(true);
        wrapItem.setOnAction(e -> {
            javaCodeArea.setWrapText(wrapItem.isSelected());
            jbcCodeArea.setWrapText(wrapItem.isSelected());
        });
        
        viewMenu.getItems().add(wrapItem);

        // Help Menu
        Menu helpMenu = new Menu("Help");
        
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAbout());
        
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(fileMenu, viewMenu, helpMenu);
        return menuBar;
    }

    private void openFile(Stage primaryStage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open TAFJ File");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Java Files", "*.java", "*.class"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) {
            try {
                processFile(file);
                statusLabel.setText("Loaded: " + file.getName());
            } catch (Exception e) {
                showError("Error loading file: " + e.getMessage());
                statusLabel.setText("Error: " + e.getMessage());
            }
        }
    }
    
    private void openJarFile(Stage primaryStage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open JAR File");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) {
            loadJarFile(file);
        }
    }

    private void processFile(File file) throws IOException {
        String content;
        
        if (file.getName().endsWith(".class")) {
            // Decompile class file first
            content = translator.decompileClass(file);
        } else {
            // Read Java source directly
            content = Files.readString(file.toPath());
        }

        // Display Java code
        javaCodeArea.clear();
        javaCodeArea.appendText(content);

        // Translate to JBC
        TranslationResult result = translator.translateToJBC(content);
        jbcCodeArea.clear();
        jbcCodeArea.appendText(result.getJbcCode());

        
    }

    private void exportJBC() {
        String jbcCode = jbcCodeArea.getText();
        if (jbcCode.isEmpty()) {
            showError("No JBC code to export");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export JBC Code");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JBC Files", "*.jbc", "*.txt")
        );

        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            try {
                Files.writeString(file.toPath(), jbcCode);
                statusLabel.setText("Exported: " + file.getName());
            } catch (IOException e) {
                showError("Error exporting file: " + e.getMessage());
            }
        }
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About TAFJ Reverse Tool");
        alert.setHeaderText("TAFJ Reverse Translation Tool v1.0");
        alert.setContentText(
            "This tool converts TAFJ-generated Java code back to JBC.\n\n" +
            "Features:\n" +
            "• Open individual .java or .class files\n" +
            "• Browse and decompile .jar files\n" +
            "• View package structure in tree view\n" +
            "• Click on any class to decompile and translate\n\n" +
            "⚠️  WARNING: Output is approximate reconstruction.\n" +
            "Verify all translated code before use.\n\n" +
            "For internal use only."
        );
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("Operation Failed");
        alert.setContentText(message);
        alert.showAndWait();
    }
}