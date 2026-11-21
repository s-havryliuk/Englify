package com.englify;

import com.englify.data.Database;
import com.englify.learning.UkrainianForms;
import com.englify.learning.SpeechService;
import com.englify.model.Stats;
import com.englify.model.VocabWord;
import com.englify.model.WordDetails;
import com.englify.ui.AppSettings;
import com.englify.ui.ThemeManager;
import javafx.animation.*;
import javafx.application.Application;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.transform.Rotate;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.paint.Color;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.function.Consumer;
import java.time.*;
import java.util.*;

public class EnglifyApp extends Application {
    private enum View { OVERVIEW, DICTIONARY, CARDS, QUIZ, CUSTOM, STATS, SETTINGS }
    private final AppSettings settings = new AppSettings();
    private final EnumMap<View,Button> navButtons = new EnumMap<>(View.class);
    private final Random random = new Random();
    private Database db;
    private Stage stage;
    private BorderPane root;
    private StackPane pageHost;
    private Scene scene;
    private View currentView;
    private String level = settings.lastLevel();
    private String topic = "Усі теми";
    private VBox currentFlipBoard;
    private String dictStatus="Усі";
    private Label sidebarLevelLabel;
    private List<Long> lastStudiedIds = List.of();
    private Label quizFeedback;
    private Round round;
    private Label liveTimer;
    private Timeline clock;
    private int themeTick = 0;
    private int dictPage = 0;
    private String dictQuery = "",dictLevel = "ALL",dictTopic = "Усі теми",dictKind = "Усі типи";
    private List<Button> quizKeys = List.of();
    private Button continueKey;
    private Button cardLearnKey, cardKnowKey;
    private boolean quizQuestionReverse=false;

    private static final class Round {
        final View mode;
        final LocalDateTime started = LocalDateTime.now();
        final long startedNanos = System.nanoTime();
        final List<VocabWord> words;
        int index=0, seen=0, correct=0;
        final List<Long> mistakes=new ArrayList<>();
        final List<Long> flippedIds=new ArrayList<>();
        boolean flipped=false, answered=false, saved=false;
        Round(View mode,List<VocabWord> words){this.mode=mode;this.words=words;}
    }

    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) throws Exception {
        this.stage = stage;
        this.db = new Database();
        root = new BorderPane();
        root.getStyleClass().add("root");
        pageHost = new StackPane();
        root.setLeft(sidebar());
        root.setCenter(pageHost);
        scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/com/englify/style.css")).toExternalForm());
        ThemeManager.apply(root,settings.theme());
        applyFontSize();
        stage.setTitle("Englify — вивчай англійську");
        stage.setMinWidth(1090);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> saveRound());
        stage.show();
        clock = new Timeline(new KeyFrame(Duration.seconds(1),e -> {
            if("Системна".equals(settings.theme()) && themeTick++ % 8 == 0) ThemeManager.apply(root,settings.theme());
            if(round!=null) {
                int elapsed=elapsed();
                int duration=settings.minutes()*60;
                if(liveTimer!=null) liveTimer.setText(duration==0?"Без обмеження часу":formatTime(Math.max(0,duration-elapsed)));
                if(duration>0 && elapsed>=duration) finishRound();
            }
        }));
        clock.setCycleCount(Animation.INDEFINITE);
        clock.play();
        scene.setOnKeyPressed(e -> {
            if(scene.getFocusOwner() instanceof TextInputControl || scene.getFocusOwner() instanceof ComboBox<?>) return;
            if(currentView==View.CARDS && round!=null) {
                if(e.getCode()==KeyCode.SPACE && !round.flipped){flipCard();e.consume();}
                else if(e.getCode()==KeyCode.LEFT && cardLearnKey!=null){cardLearnKey.fire();e.consume();}
                else if(e.getCode()==KeyCode.RIGHT && cardKnowKey!=null){cardKnowKey.fire();e.consume();}
            } else if(currentView==View.QUIZ && round!=null) {
                int number=switch(e.getCode()){
                    case DIGIT1, NUMPAD1 -> 0;
                    case DIGIT2, NUMPAD2 -> 1;
                    case DIGIT3, NUMPAD3 -> 2;
                    case DIGIT4, NUMPAD4 -> 3;
                    default -> -1;
                };
                if(number>=0 && number<quizKeys.size() && !round.answered){quizKeys.get(number).fire();e.consume();}
                else if(e.getCode()==KeyCode.ENTER && continueKey!=null && round.answered){continueKey.fire();e.consume();}
            }
        });
        overview();
    }

    private VBox sidebar() {
        VBox nav = new VBox(6);
        nav.getStyleClass().add("sidebar");
        nav.setPrefWidth(218);nav.setMinWidth(218);
        Region logoBack=new Region();logoBack.setStyle("-fx-background-color: transparent; -fx-border-color: #B0C20E; -fx-border-width: 2.6; -fx-border-radius: 3;");
        logoBack.setPrefSize(17,21);logoBack.setMaxSize(17,21);logoBack.setRotate(-17);logoBack.setTranslateX(-3);
        Region logoFront=new Region();logoFront.setStyle("-fx-background-color: #181B22; -fx-border-color: #B0C20E; -fx-border-width: 2.6; -fx-border-radius: 3;");
        logoFront.setPrefSize(17,21);logoFront.setMaxSize(17,21);logoFront.setRotate(-17);logoFront.setTranslateX(4);
        StackPane logo=new StackPane(logoBack,logoFront);logo.setMinSize(30,30);logo.setPrefSize(30,30);
        HBox branding=row(10,logo,label("Englify","brand"));
        branding.setPadding(new Insets(0,0,17,8));nav.getChildren().add(branding);
        addNav(nav,View.OVERVIEW,"Головна",this::overview);
        addNav(nav,View.CARDS,"Картки",()->startRound(View.CARDS));
        addNav(nav,View.QUIZ,"Тестування",()->startRound(View.QUIZ));
        addNav(nav,View.DICTIONARY,"Словник",this::dictionary);
        addNav(nav,View.CUSTOM,"Мої слова",this::custom);
        addNav(nav,View.STATS,"Прогрес",this::stats);
        Region spacer=new Region();VBox.setVgrow(spacer,Priority.ALWAYS);
        nav.getChildren().add(spacer);
        sidebarLevelLabel=label("Рівень "+level,"sidebar-level");
        nav.getChildren().add(sidebarLevelLabel);
        addNav(nav,View.SETTINGS,"Налаштування",this::preferences);
        return nav;
    }

    private void addNav(VBox sidebar,View v,String text,Runnable action) {
        Button b=new Button(text);
        b.setGraphic(v==View.SETTINGS?label("⚙","nav-gear"):icon(v));
        b.getStyleClass().add("nav-link");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e->action.run());
        navButtons.put(v,b);sidebar.getChildren().add(b);
    }
    private SVGPath icon(View view) {
        String data=switch(view) {
            case OVERVIEW -> "M3 10L12 3L21 10V20H15V14H9V20H3Z";
            case DICTIONARY -> "M5 3H19V21H6C4 21 3 20 3 18V5C3 4 4 3 5 3ZM7 7V9H16V7ZM7 12V14H16V12Z";
            case CARDS -> "M3 5H17V17H3ZM7 1H21V13H19V3H7ZM6 9H14V11H6Z";
            case QUIZ -> "M4 3H20V21H4ZM8 7V9H10V7ZM12 7V9H17V7ZM8 12V14H10V12ZM12 12V14H17V12Z";
            case CUSTOM -> "M4 3H15L20 8V21H4ZM8 12V14H11V17H13V14H16V12H13V9H11V12Z";
            case STATS -> "M4 20V12H7V20ZM10 20V5H13V20ZM16 20V9H19V20Z";
            case SETTINGS -> "M12 3A9 9 0 1 1 3 12A9 9 0 0 1 12 3ZM12 8A4 4 0 1 0 12 16A4 4 0 0 0 12 8Z";
        };
        SVGPath path=new SVGPath();path.setContent(data);path.getStyleClass().add("nav-icon");
        path.setScaleX(.78);path.setScaleY(.78);
        return path;
    }
    private void render(View v,VBox page) {
        if(round!=null && v!=round.mode) saveRound();
        currentView=v;
        page.getStyleClass().add("page");
        page.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);
        for(Map.Entry<View,Button> item:navButtons.entrySet()){
            item.getValue().getStyleClass().remove("nav-active");
            if(item.getKey()==v)item.getValue().getStyleClass().add("nav-active");
        }
        if(sidebarLevelLabel!=null) sidebarLevelLabel.setText("Рівень "+level);
        root.setLeft(sidebarVisible());
        pageHost.getChildren().setAll(page);
    }
    private VBox sidebarVisible(){
        // Reuse the same navigation nodes; no duplicate actions or reset state.
        return (VBox) navButtons.get(View.OVERVIEW).getParent();
    }
    private VBox page(){return new VBox(16);}
    private Label label(String t,String...styles) {
        Label l=new Label(t);l.getStyleClass().addAll(styles);l.setWrapText(true);return l;
    }
    private HBox row(double space,Node...nodes){HBox h=new HBox(space,nodes);h.setAlignment(Pos.CENTER_LEFT);return h;}
    private VBox column(double space,Node...nodes){return new VBox(space,nodes);}
    private Region flex(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    private Region vflex(){Region r=new Region();VBox.setVgrow(r,Priority.ALWAYS);return r;}
    private VBox card(Node...nodes){VBox b=column(11,nodes);b.getStyleClass().add("surface");return b;}
    private Button button(String title,String style,Runnable callback){
        Button b=new Button(title);b.getStyleClass().add(style);
        b.setOnAction(e->callback.run());return b;
    }
    private Button primary(String title,Runnable callback){return button(title,"primary-btn",callback);}
    private Button outline(String title,Runnable callback){return button(title,"outline-btn",callback);}
    private Button soft(String title,Runnable callback){return button(title,"soft-btn",callback);}
    private HBox heading(String title,String subtitle){
        VBox text=column(4,label(title,"page-title"),label(subtitle,"page-subtitle"));
        HBox h=row(15,text,flex(),themeControls());h.setAlignment(Pos.CENTER_LEFT);return h;
    }
    /** Quick light/dark toggle lives in the content header; System remains available in Settings. */
    private HBox themeControls(){
        Button night=button("☾","theme-toggle",()->setTheme("Темна"));
        Button day=button("☼","theme-toggle",()->setTheme("Світла"));
        if(settings.theme().equals("Темна"))night.getStyleClass().add("theme-toggle-selected");
        if(settings.theme().equals("Світла"))day.getStyleClass().add("theme-toggle-selected");
        HBox h=row(2,night,day);h.getStyleClass().add("theme-toggle-box");return h;
    }
    private void setTheme(String name){
        settings.theme(name);ThemeManager.apply(root,name);
        // Refresh just the header so the newly selected toggle is visibly active.
        switch(currentView){
            case OVERVIEW -> overview(); case DICTIONARY -> dictionary();
            case CUSTOM -> custom(); case STATS -> stats(); case SETTINGS -> preferences();
            case CARDS,QUIZ -> { /* Keep the current study question and its state. */ }
            default -> { }
        }
    }
    /** Custom SVG-free goal ring: unlike ProgressIndicator it cannot cover the center label. */
    private StackPane progressRing(double fraction,int diameter,String main,String caption){
        double r=diameter*.395;
        Circle track=new Circle(r);track.setFill(Color.TRANSPARENT);track.setStroke(Color.web("#52627D"));track.setStrokeWidth(diameter*.105);
        StackPane circle=new StackPane(track);
        fraction=Math.max(0,Math.min(1,fraction));
        if(fraction>=.999){
            Circle full=new Circle(r);full.setFill(Color.TRANSPARENT);full.setStroke(Color.web("#B0C20E"));full.setStrokeWidth(diameter*.105);
            circle.getChildren().add(full);
        }else if(fraction>0){
            Arc arc=new Arc(0,0,r,r,90,-360*fraction);arc.setType(ArcType.OPEN);arc.setFill(Color.TRANSPARENT);
            arc.setStroke(Color.web("#B0C20E"));arc.setStrokeWidth(diameter*.105);arc.setStrokeLineCap(StrokeLineCap.ROUND);
            circle.getChildren().add(arc);
        }
        VBox numbers=column(0,label(main,"ring-number"),label(caption,"ring-caption"));numbers.setAlignment(Pos.CENTER);
        circle.getChildren().add(numbers);circle.setMinSize(diameter,diameter);circle.setPrefSize(diameter,diameter);
        circle.setMaxSize(diameter,diameter);return circle;
    }
    private void applyFontSize(){
        root.getStyleClass().removeAll("font-small","font-medium","font-large");
        root.getStyleClass().add(switch(settings.fontSize()){
            case "Маленький" -> "font-small";
            case "Великий" -> "font-large";
            default -> "font-medium";
        });
    }
    private ProgressBar bar(double p){ProgressBar b=new ProgressBar(Math.max(0,Math.min(1,p)));b.setMaxWidth(Double.MAX_VALUE);b.setPrefHeight(8);return b;}
    private void grow(Node n){HBox.setHgrow(n,Priority.ALWAYS);if(n instanceof Region r)r.setMaxWidth(Double.MAX_VALUE);}
    /** Highlight the target in a real usage sentence rather than showing unrelated filler. */
    private TextFlow exampleText(String sentence,String target){
        TextFlow flow=new TextFlow();flow.getStyleClass().add("example-flow");
        if(sentence==null||sentence.isBlank())return flow;
        String original=sentence.toLowerCase(Locale.ROOT);
        String key=target.toLowerCase(Locale.ROOT);
        int at=original.indexOf(key);
        if(at<0){Text t=new Text(sentence);t.getStyleClass().add("example-plain");flow.getChildren().add(t);return flow;}
        Text before=new Text(sentence.substring(0,at));before.getStyleClass().add("example-plain");
        Text word=new Text(sentence.substring(at,at+target.length()));word.getStyleClass().add("example-highlight");
        Text after=new Text(sentence.substring(at+target.length()));after.getStyleClass().add("example-plain");
        flow.getChildren().addAll(before,word,after);return flow;
    }
    private void error(Exception e){
        Alert a=new Alert(Alert.AlertType.ERROR);
        a.setTitle("Englify — помилка");a.setHeaderText("Не вдалося виконати операцію");
        a.setContentText(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());a.showAndWait();
    }
    private Stats readStats() {
        try{return db.stats();}catch(SQLException e){error(e);return new Stats(0,0,0,0,0,0,0,0,0,Map.of("A1",0,"A2",0,"B1",0),Map.of("A1",0,"A2",0,"B1",0),Map.of());}
    }

    private void overview() {
        if(round!=null)saveRound();
        Stats st=readStats();
        VBox page=page();page.getStyleClass().add("overview-page");
        HBox title=row(12,label("Продовжимо?","page-title"),flex(),
                label("🔥  "+st.streak()+" дн.","streak-pill"),themeControls());
        page.getChildren().add(title);
        int due=0;
        try{due=db.dueCount(level);}catch(SQLException e){error(e);}
        int completed=Math.min(st.studiedToday(),settings.dailyGoal());
        int remaining=Math.max(0,settings.dailyGoal()-st.studiedToday());
        StackPane ring=progressRing(completed/(double)Math.max(1,settings.dailyGoal()),132,
                completed+"/"+settings.dailyGoal(),"ціль");
        VBox heroBody=column(10,
                label("Сьогодні на повторення","hero-copy"),
                label(UkrainianForms.words(due),"hero-number"),
                label(remaining==0?"Денну ціль виконано ✓":"До цілі ще "+UkrainianForms.words(remaining),"hero-copy"));
        Button repeat=primary("▶   Почати повторення",this::startReviewRound);repeat.setMinWidth(220);
        Button fresh=outline("Нові слова",()->startNewWordsRound());
        VBox actions=column(8,repeat,fresh);actions.setAlignment(Pos.CENTER_LEFT);
        heroBody.getChildren().add(actions);
        HBox hero=row(24,ring,heroBody);hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("hero-panel");hero.setMinHeight(247);hero.setPrefHeight(247);
        HBox.setHgrow(hero,Priority.ALWAYS);

        VBox word=card(label("СЛОВО ДНЯ","eyebrow"));word.getStyleClass().add("word-of-day");
        word.setMinWidth(300);word.setPrefWidth(335);word.setMinHeight(247);word.setPrefHeight(247);
        try{
            Optional<VocabWord> chosen=db.wordOfDay(level);
            if(chosen.isPresent()){
                VocabWord w=chosen.get();
                HBox name=row(9,label(w.english(),"word-day-title"),flex(),
                        button("🔊","sound-btn",()->SpeechService.speak(w.english())));
                word.getChildren().addAll(name,label(w.ukrainian(),"word-day-meaning"));
                Optional<WordDetails> d=db.details(w.id());
                if(d.isPresent()){
                    WordDetails details=d.get();
                    if(details.ipa()!=null&&!details.ipa().isBlank())word.getChildren().add(label(details.ipa(),"word-day-ipa"));
                    if(details.exampleEn()!=null&&!details.exampleEn().isBlank())word.getChildren().add(exampleText(details.exampleEn(),w.english()));
                    if(details.exampleUk()!=null&&!details.exampleUk().isBlank())word.getChildren().add(label(details.exampleUk(),"word-day-translation"));
                }
            }else{word.getChildren().add(label("Вивчай нові слова, щоб поповнити словник.","muted"));}
        }catch(SQLException e){error(e);}
        HBox top=row(16,hero,word);top.setAlignment(Pos.TOP_LEFT);page.getChildren().add(top);

        VBox journey=card(label("Шлях рівнів","section-title"));journey.getStyleClass().add("journey-panel");
        HBox levels=row(12);levels.setAlignment(Pos.CENTER);
        for(String l:List.of("A1","A2","B1")){
            VBox tile=levelTile(l,st);HBox.setHgrow(tile,Priority.ALWAYS);levels.getChildren().add(tile);
        }
        journey.getChildren().add(levels);page.getChildren().add(journey);
        render(View.OVERVIEW,page);
    }

    private VBox levelTile(String l,Stats s){
        int total=s.levelsTotal().getOrDefault(l,0),mastered=s.levelsMastered().getOrDefault(l,0);
        StackPane progress=progressRing(total==0?0:mastered/(double)total,82,l,"");
        VBox tile=column(8,progress,label(mastered+" / "+total,"level-subtitle"));tile.setAlignment(Pos.CENTER);
        tile.getStyleClass().add("level-step");tile.setMaxWidth(Double.MAX_VALUE);tile.setPrefHeight(126);
        if(l.equals(level))tile.getStyleClass().add("level-selected");
        tile.setOnMouseClicked(e->selectLevel(l));return tile;
    }
    private void startReviewRound(){
        saveRound();
        try{
            List<VocabWord> due=db.dueWords(level,settings.words());
            if(due.isEmpty()){
                toast("Наразі немає слів для повторення — спробуй нові картки");return;
            }
            round=new Round(View.CARDS,due);drawRound();
        }catch(SQLException e){error(e);}
    }
    private void startNewWordsRound(){
        saveRound();
        try{
            List<VocabWord> words=db.unseenWords(level,settings.words());
            if(words.isEmpty()){toast("Усі слова цього рівня вже зустрічалися. Почни повторення.");return;}
            round=new Round(View.CARDS,words);drawRound();
        }catch(SQLException e){error(e);}
    }

    private String levelBeforeChange = "A1";
    private void changeLevel(String requested){
        levelBeforeChange=level;
        level=requested;topic="Усі теми";settings.lastLevel(level);
    }
    private void selectLevel(String requested){
        if(requested.equals(level))return;
        changeLevel(requested);
        overview();
        if(rankLevel(requested)>rankLevel(levelBeforeChange)) welcomeLevel(requested);
    }
    private int rankLevel(String l){return List.of("A1","A2","B1").indexOf(l);}
    private void welcomeLevel(String l){
        VBox box=column(13,label("НОВИЙ ЕТАП", "accent-text"),label("Вітаємо з вибором "+l+"!","dialog-title"),
                label("Ти перейшов до добірок рівня "+l+". Попередні рівні також залишаються доступними.","muted"));
        box.getStyleClass().add("dialog-body");box.setMaxWidth(455);
        StackPane background=new StackPane(box);background.getStyleClass().add("dialog-scrim");
        background.setOnMouseClicked(e->pageHost.getChildren().remove(background));
        box.setOnMouseClicked(e->e.consume());
        Button close=primary("Почати навчання",()->{pageHost.getChildren().remove(background);startRound(View.CARDS);});
        box.getChildren().add(close);
        pageHost.getChildren().add(background);
        box.setScaleX(.93);box.setScaleY(.93);
        ScaleTransition t=new ScaleTransition(Duration.millis(190),box);t.setToX(1);t.setToY(1);t.play();
    }

    private void dictionary(){
        VBox page=page();page.getChildren().add(heading("Словник","Слова, з якими ти вже працював"));
        TextField search=new TextField(dictQuery);search.setPromptText("⌕  Пошук слова або перекладу...");search.setMaxWidth(Double.MAX_VALUE);
        Runnable load=()->{dictPage=0;dictionary();};
        search.setOnAction(e->{dictQuery=search.getText().trim();load.run();});
        Button find=soft("Знайти",()->{dictQuery=search.getText().trim();load.run();});
        HBox searchBar=row(10,search,find);grow(search);page.getChildren().add(searchBar);
        HBox filters=row(7);
        for(String state:List.of("Усі","Нові","Вивчаю","Опановані")){
            Button b=soft(state,()->{dictStatus=state;dictPage=0;dictionary();});
            if(state.equals(dictStatus))b.getStyleClass().add("filter-selected");filters.getChildren().add(b);
        }
        page.getChildren().add(filters);
        try{
            List<VocabWord> all=db.seenWords(dictQuery,"Усі типи",10000,0);
            Set<Long> mastered=db.masteredWordIds();Set<Long> newToday=db.firstSeenTodayIds();
            List<VocabWord> words=all.stream().filter(w->switch(dictStatus){
                case "Нові" -> newToday.contains(w.id());
                case "Вивчаю" -> !mastered.contains(w.id());
                case "Опановані" -> mastered.contains(w.id());
                default -> true;
            }).toList();
            ListView<VocabWord> list=new ListView<>();list.getStyleClass().add("word-list");list.getItems().setAll(words);
            list.setCellFactory(v->new ListCell<>(){
                @Override protected void updateItem(VocabWord w,boolean empty){
                    super.updateItem(w,empty);
                    if(empty||w==null){setText(null);setGraphic(null);return;}
                    Label dot=label("●",mastered.contains(w.id())?"word-mastered":newToday.contains(w.id())?"word-new":"word-learning");
                    VBox name=column(2,label(w.english(),"list-word"),label(w.ukrainian(),"muted"));
                    HBox content=row(10,dot,name,flex(),label(w.level(),"word-kind"));
                    content.setPadding(new Insets(6));setGraphic(content);setText(null);
                }
            });
            VBox detail=card(label("Обери слово зі списку","section-title"),label("Тут з’являться переклад, приклад і прогрес повторень.","muted"));
            detail.setPrefWidth(300);detail.getStyleClass().add("word-detail");
            list.getSelectionModel().selectedItemProperty().addListener((obs,old,w)->{
                if(w==null)return;
                detail.getChildren().clear();
                detail.getChildren().addAll(row(10,label(w.english(),"section-title"),flex(),
                    button("🔊","round-sound",()->SpeechService.speak(w.english()))),
                    label(w.ukrainian(),"flash-translation"),
                    label(mastered.contains(w.id())?"Опановане":newToday.contains(w.id())?"Сьогодні додано":"Вивчається","muted"));
                try{
                    Optional<WordDetails> data=db.details(w.id());
                    data.ifPresent(d->{
                        if(d.ipa()!=null&&!d.ipa().isBlank())detail.getChildren().add(label(d.ipa(),"flash-ipa"));
                        if(d.exampleEn()!=null&&!d.exampleEn().isBlank())detail.getChildren().add(exampleText(d.exampleEn(),w.english()));
                        if(d.exampleUk()!=null&&!d.exampleUk().isBlank())detail.getChildren().add(label(d.exampleUk(),"example-uk"));
                    });
                }catch(SQLException ex){error(ex);}
                detail.getChildren().add(outline("＋ До моїх слів",()->{
                    try{db.saveWord(w.id());toast("Слово збережено");}catch(SQLException ex){error(ex);}
                }));
            });
            if(!words.isEmpty())list.getSelectionModel().selectFirst();
            HBox main=row(14,list,detail);grow(list);list.setPrefWidth(450);
            VBox.setVgrow(main,Priority.ALWAYS);page.getChildren().add(main);
            page.getChildren().add(label("У словнику: "+words.size()+" слів · дані зберігаються між запусками","muted"));
        }catch(SQLException ex){error(ex);}
        render(View.DICTIONARY,page);
    }

    private List<String> safeTopics(String selected){
        try{return db.topics(selected);}catch(SQLException e){error(e);return List.of("Усі теми");}
    }
    private TableView<VocabWord> table(List<VocabWord> values,Set<Long> masteredIds){
        TableView<VocabWord> t=new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<VocabWord,String> en=new TableColumn<>("English");
        en.setCellValueFactory(c->new javafx.beans.property.SimpleStringProperty(c.getValue().english()));en.setPrefWidth(220);
        TableColumn<VocabWord,String> uk=new TableColumn<>("Українською");
        uk.setCellValueFactory(c->new javafx.beans.property.SimpleStringProperty(c.getValue().ukrainian()));uk.setPrefWidth(300);
        TableColumn<VocabWord,String> stateCol=new TableColumn<>("Статус");
        stateCol.setCellValueFactory(c->new javafx.beans.property.SimpleStringProperty(
                masteredIds.contains(c.getValue().id())?"Засвоєне":"Вивчається"));
        stateCol.setPrefWidth(140);
        TableColumn<VocabWord,String> typeCol=new TableColumn<>("Тип");
        typeCol.setCellValueFactory(c->new javafx.beans.property.SimpleStringProperty(
                c.getValue().english().trim().contains(" ")?"Фраза":"Слово"));
        typeCol.setPrefWidth(85);
        t.getColumns().addAll(en,uk,typeCol,stateCol);
        t.getItems().setAll(values);
        t.setPlaceholder(label("Тут з’являться слова після перегортання карток.","muted"));
        return t;
    }
    private <T> ComboBox<T> combo(List<T> values,T selected) {
        ComboBox<T> box=new ComboBox<>();box.getItems().setAll(values);box.setValue(selected);
        return box;
    }

    private void startRound(View mode) {
        saveRound();
        List<VocabWord> chosen;
        try {
            if(mode==View.QUIZ){
                chosen=!lastStudiedIds.isEmpty()?db.wordsByIds(lastStudiedIds):db.seenWords("","Усі типи",settings.words(),0);
                if(chosen.size()>settings.words())chosen=chosen.subList(0,settings.words());
            } else chosen=db.studyWords(level,topic,settings.words());
        } catch(SQLException e){error(e);return;}
        if(chosen.isEmpty()){
            toast(mode==View.QUIZ?"Спочатку перегорни декілька карток — тоді відкриється тест":"У цій добірці поки немає слів");
            overview();return;
        }
        round=new Round(mode,chosen);
        drawRound();
    }
    private void startMistakeRound(View mode,List<Long> ids) {
        if(ids.isEmpty())return;
        try {round=new Round(mode,db.wordsByIds(ids));drawRound();}
        catch(SQLException e){error(e);}
    }
    private void drawRound() {
        if(round==null)return;
        if(round.index>=round.words.size()) {finishRound();return;}
        VocabWord word=round.words.get(round.index);
        quizKeys=List.of();continueKey=null;cardLearnKey=null;cardKnowKey=null;
        VBox page=page();page.getStyleClass().add("study-page");
        page.getChildren().add(heading(round.mode==View.CARDS?"Картки":"Тестування",
                round.mode==View.CARDS?"Вивчай у своєму темпі":"Перевір знайомі слова"));
        Button close=outline("✕",()->{saveRound();overview();});close.setMinWidth(44);
        HBox segments=row(4);segments.setAlignment(Pos.CENTER);
        for(int i=0;i<round.words.size();i++){
            Region segment=new Region();segment.getStyleClass().add(i<round.index?"segment-done":"segment-todo");
            segment.setPrefHeight(8);segment.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(segment,Priority.ALWAYS);
            segments.getChildren().add(segment);
        }
        HBox top=row(12,close,segments,label((round.index+1)+"/"+round.words.size(),"muted"));
        top.setMaxWidth(785);grow(segments);
        if(round.mode==View.QUIZ){
            liveTimer=label("", "muted");top.getChildren().add(liveTimer);
        } else liveTimer=null;
        HBox topCenter=row(0,top);topCenter.setAlignment(Pos.CENTER);page.getChildren().add(topCenter);
        if(round.mode==View.CARDS)buildFlashcard(page,word);
        else {
            quizQuestionReverse=switch(settings.testDirection()) {
                case "UA_EN" -> true; case "Змішано" -> random.nextBoolean(); default -> false;
            };
            buildQuiz(page,word);
        }
        render(round.mode,page);
    }

    private void buildFlashcard(VBox page,VocabWord word){
        Optional<WordDetails> d=Optional.empty();
        try{d=db.details(word.id());}catch(SQLException ex){error(ex);}
        String ipa=d.map(WordDetails::ipa).orElse("");
        Label prompt=label(word.english(),"flash-word");prompt.setAlignment(Pos.CENTER);
        VBox middle=column(14,label(word.english().contains(" ")?"ФРАЗА":"СЛОВО","word-kind"),prompt,
                label(ipa,"flash-ipa"),button("🔊","round-sound",()->SpeechService.speak(word.english())));
        middle.setAlignment(Pos.CENTER);
        VBox board=column(0,vflex(),middle,vflex());board.getStyleClass().add("flashcard");
        board.setAlignment(Pos.CENTER);board.setMinHeight(280);board.setMaxWidth(780);
        board.setOnMouseClicked(e->flipCard());currentFlipBoard=board;
        VBox centered=column(12,board);centered.setAlignment(Pos.TOP_CENTER);
        page.getChildren().add(centered);
        Button flip=outline("⟳  Показати відповідь · Space",this::flipCard);
        flip.setMinHeight(54);flip.setMaxWidth(780);
        VBox controls=column(12,flip);
        cardLearnKey=soft("Ще вчу",()->reviewCard(false));
        cardKnowKey=primary("Знаю",()->reviewCard(true));
        HBox actions=row(12,cardLearnKey,cardKnowKey);grow(cardLearnKey);grow(cardKnowKey);
        controls.getChildren().add(actions);controls.setMaxWidth(780);controls.setAlignment(Pos.CENTER);
        VBox wrapper=column(4,controls,label("Пробіл — перегорнути   •   ← ще вчу   •   → знаю","tiny"));
        wrapper.setAlignment(Pos.CENTER);
        page.getChildren().add(wrapper);
    }

    private void flipCard(){
        if(round==null||round.mode!=View.CARDS||round.flipped||currentView!=View.CARDS)return;
        VocabWord word=round.words.get(round.index);
        VBox board=currentFlipBoard;
        if(board==null)return;
        board.setRotationAxis(Rotate.Y_AXIS);
        RotateTransition first=new RotateTransition(Duration.millis(130),board);
        first.setFromAngle(0);first.setToAngle(90);
        first.setOnFinished(e->{
            VBox back=column(14,label(word.ukrainian(),"flash-translation"));
            back.setAlignment(Pos.CENTER);
            try{
                Optional<WordDetails> d=db.details(word.id());
                d.ifPresent(details->{
                    if(details.exampleEn()!=null&&!details.exampleEn().isBlank())back.getChildren().add(exampleText(details.exampleEn(),word.english()));
                    if(details.exampleUk()!=null&&!details.exampleUk().isBlank())back.getChildren().add(label(details.exampleUk(),"example-uk"));
                });
            }catch(SQLException ex){error(ex);}
            if(back.getChildren().size()==1)back.getChildren().add(label("Приклад для цього слова ще готується","example-uk"));
            back.getChildren().add(button("🔊  "+word.english(),"round-sound",()->SpeechService.speak(word.english())));
            board.getChildren().setAll(vflex(),back,vflex());
            board.setRotate(-90);
            RotateTransition second=new RotateTransition(Duration.millis(130),board);second.setToAngle(0);second.play();
        });first.play();
        try{db.recordFlip(word.id());round.flippedIds.add(word.id());}catch(SQLException ex){error(ex);}
        round.flipped=true;
    }
    private void reviewCard(boolean recalled){
        if(round==null||round.mode!=View.CARDS)return;
        VocabWord word=round.words.get(round.index);
        if(!round.flippedIds.contains(word.id()))round.flippedIds.add(word.id());
        try{
            if(!round.flipped){db.recordFlip(word.id());}
            db.recordCardReview(word.id(),recalled);
        }catch(SQLException ex){error(ex);return;}
        round.seen++;if(recalled)round.correct++;else round.mistakes.add(word.id());
        round.index++;round.flipped=false;drawRound();
    }

    private String answerText(VocabWord w){return quizQuestionReverse?w.english():w.ukrainian();}
    private String questionText(VocabWord w){return quizQuestionReverse?w.ukrainian():w.english();}
    private void buildQuiz(VBox page,VocabWord word){
        Label under=label("","quiz-underword");
        VBox question=column(12,label("Обери правильний переклад","question-instruction"),
            label(questionText(word),"question-word"),under);
        question.setAlignment(Pos.CENTER);question.getStyleClass().add("quiz-question");question.setMinHeight(200);question.setMaxWidth(780);
        VBox center=column(0,question);center.setAlignment(Pos.CENTER);
        page.getChildren().add(center);
        List<VocabWord> options=new ArrayList<>();options.add(word);
        Set<String> seen=new HashSet<>();seen.add(answerText(word).toLowerCase(Locale.ROOT));
        try {
            for(VocabWord candidate:db.distractorsLike(word,100)) {
                if(seen.add(answerText(candidate).toLowerCase(Locale.ROOT))){options.add(candidate);if(options.size()==4)break;}
            }
            if(options.size()<4)for(VocabWord candidate:db.distractors(word.level(),word.id(),150)){
                if(seen.add(answerText(candidate).toLowerCase(Locale.ROOT))){options.add(candidate);if(options.size()==4)break;}
            }
        }catch(SQLException ex){error(ex);}
        Collections.shuffle(options,random);
        GridPane grid=new GridPane();grid.setHgap(12);grid.setVgap(12);grid.setMaxWidth(780);
        for(int i=0;i<2;i++){ColumnConstraints cc=new ColumnConstraints();cc.setPercentWidth(50);cc.setHgrow(Priority.ALWAYS);grid.getColumnConstraints().add(cc);}
        List<Button> buttons=new ArrayList<>();
        Label feedback=label("Обери відповідь або натисни 1–4","muted");
        Button next=primary(round.index==round.words.size()-1?"Готово":"Далі  →",()->{round.index++;round.answered=false;drawRound();});
        next.setDisable(true);continueKey=next;
        VBox feedbackBox=column(9,feedback,next);feedbackBox.setAlignment(Pos.CENTER);
        for(int i=0;i<options.size();i++){
            VocabWord option=options.get(i);int idx=i;
            Button b=new Button((i+1)+"   "+answerText(option));
            b.getStyleClass().add("quiz-option");b.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);
            GridPane.setHgrow(b,Priority.ALWAYS);GridPane.setVgrow(b,Priority.ALWAYS);
            b.setOnAction(e->{
                if(round==null||round.answered)return;
                boolean ok=option.id()==word.id();round.answered=true;round.seen++;
                if(ok)round.correct++;else round.mistakes.add(word.id());
                try{db.recordAnswer(word.id(),ok);}catch(SQLException ex){error(ex);}
                for(int j=0;j<buttons.size();j++){
                    Button item=buttons.get(j);item.setDisable(true);
                    if(options.get(j).id()==word.id()){item.getStyleClass().add("answer-correct");item.setText("✓   "+answerText(options.get(j)));}
                    else if(item==b){item.getStyleClass().add("answer-wrong");item.setText("✕   "+answerText(options.get(j)));}
                }
                under.setText(ok?"✓  Правильно!":"✕  Не хвилюйся, ти ще вчишся");
                under.getStyleClass().add(ok?"feedback-good":"feedback-bad");
                feedback.setText(ok?"Чудова робота!":"Правильна відповідь: "+answerText(word));
                feedbackBox.getStyleClass().add(ok?"feedback-panel-good":"feedback-panel-bad");
                next.setDisable(false);
            });
            buttons.add(b);grid.add(b,i%2,i/2);
        }
        quizKeys=buttons;
        VBox optionsArea=column(14,grid,feedbackBox);optionsArea.setAlignment(Pos.CENTER);
        page.getChildren().add(optionsArea);
    }

    private int elapsed(){return round==null?0:(int)((System.nanoTime()-round.startedNanos)/1_000_000_000L);}
    private String formatTime(int sec){return String.format(Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    private void saveRound(){
        if(round==null||round.saved)return;
        round.saved=true;
        if(round.mode==View.CARDS && !round.flippedIds.isEmpty())lastStudiedIds=List.copyOf(new LinkedHashSet<>(round.flippedIds));
        try{db.recordSession(round.started,round.mode.name(),round.seen,elapsed());}
        catch(SQLException e){error(e);}
        round=null;liveTimer=null;quizKeys=List.of();continueKey=null;
    }
    private void finishRound(){
        if(round==null)return;
        int seen=round.seen,correct=round.correct,seconds=elapsed();
        List<Long> missed=List.copyOf(new LinkedHashSet<>(round.mistakes));
        View mode=round.mode;
        saveRound();
        VBox page=page();
        page.getChildren().add(heading("Сесію завершено!","Результати й наступний крок"));
        VBox center=card(label("ПІДСУМОК", "eyebrow"),label(correct+" / "+seen,"display-number"),
                label(mode==View.QUIZ?"правильних відповідей":"відмічено як «знаю»", "muted"),
                label("Час: "+formatTime(seconds)+" · "+UkrainianForms.words(missed.size())+" для повторення", "muted"));
        if(!missed.isEmpty()) {
            try {
                List<VocabWord> words=db.wordsByIds(missed);
                String names=words.stream().limit(6).map(VocabWord::english).reduce((a,b)->a+" · "+b).orElse("");
                center.getChildren().add(label("Ще варто повторити: "+names,"muted"));
            }catch(SQLException e){error(e);}
        }
        HBox actions=row(12,primary(mode==View.CARDS?"Перевірити слова":"Нова сесія",()->startRound(mode==View.CARDS?View.QUIZ:View.QUIZ)),outline("На головну",this::overview));
        if(!missed.isEmpty())actions.getChildren().add(soft("Повторити помилки",()->startMistakeRound(mode,missed)));
        center.getChildren().add(actions);
        center.setMinHeight(280);center.setAlignment(Pos.CENTER);
        page.getChildren().add(center);
        render(mode,page);
    }

    private void custom(){
        VBox page=page();
        page.getChildren().add(heading("Мої слова","Додавай власні картки та цілі списки без зайвих кроків"));
        TextField en=new TextField();en.setPromptText("English word");
        TextField uk=new TextField();uk.setPromptText("Переклад українською");
        ComboBox<String> lv=combo(List.of("A1","A2","B1"),level);
        Button add=primary("＋ Додати",()->{
            try{
                String english=en.getText().trim(),ukrainian=uk.getText().trim();
                if(english.isBlank()!=ukrainian.isBlank()){
                    Optional<VocabWord> known=db.findKnown(english.isBlank()?ukrainian:english);
                    if(known.isPresent()){
                        english=known.get().english();ukrainian=known.get().ukrainian();
                    }else{
                        throw new IllegalArgumentException("Цього слова поки немає у словнику. Вкажи також переклад.");
                    }
                }
                db.addCustom(english,ukrainian,lv.getValue());
                custom();toast("Картку додано до «Мої слова»");
            }catch(Exception e){error(e);}
        });
        VBox form=card(label("ШВИДКЕ ДОДАВАННЯ","eyebrow"),en,uk,row(10,lv,add));
        TextArea bulk=new TextArea();
        bulk.setPromptText("Одне слово або пара перекладів у кожному рядку");
        bulk.setPrefRowCount(5);bulk.setMaxHeight(137);
        Button importButton=outline("Імпортувати список",()->{
            String[] lines=bulk.getText().split("\\R");int added=0,missing=0;
            for(String line:lines){
                if(line.isBlank())continue;
                try{
                    String[] pair=line.trim().split("\\s+[–—-]\\s+|\\t",2);
                    if(pair.length==2) {db.addCustom(pair[0],pair[1],lv.getValue());added++;}
                    else{
                        Optional<VocabWord> found=db.findKnown(line.trim());
                        if(found.isPresent()) {VocabWord w=found.get();db.addCustom(w.english(),w.ukrainian(),lv.getValue());added++;}
                        else missing++;
                    }
                }catch(Exception e){missing++;}
            }
            custom();toast("Імпортовано: "+added+" · без перекладу: "+missing);
        });
        VBox mass=card(label("ІМПОРТ КІЛЬКОХ СЛІВ","eyebrow"),
                label("Приклад: reliable — надійний · application — застосунок", "tiny"),
                bulk,importButton);
        VBox left=column(13,form,mass);
        try{
            List<VocabWord> own=db.search("ALL","Усі теми",true,"",5000,0);
            TableView<VocabWord> table=ownTable(own);
            table.setPrefHeight(468);
            VBox.setVgrow(table,Priority.ALWAYS);
            Button del=outline("Видалити вибране",()->{
                VocabWord selected=table.getSelectionModel().getSelectedItem();
                if(selected==null)return;
                try{db.deleteCustom(selected.id());custom();toast("Картку вилучено з особистої колекції");}
                catch(SQLException e){error(e);}
            });
            VBox right=card(label("МОЇ КАРТКИ · "+db.countSearch("ALL","Усі теми",true,""),"eyebrow"),table,del);
            HBox main=row(14,left,right);grow(left);grow(right);
            VBox.setVgrow(main,Priority.ALWAYS);
            page.getChildren().add(main);

        }catch(SQLException e){error(e);}
        render(View.CUSTOM,page);
    }
    private TableView<VocabWord> ownTable(List<VocabWord> values){
        TableView<VocabWord> table=new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<VocabWord,String> english=new TableColumn<>("English");
        english.setCellValueFactory(c->new javafx.beans.property.SimpleStringProperty(c.getValue().english()));
        TableColumn<VocabWord,String> ukrainian=new TableColumn<>("Переклад");
        ukrainian.setCellValueFactory(c->new javafx.beans.property.SimpleStringProperty(c.getValue().ukrainian()));
        table.getColumns().addAll(english,ukrainian);
        table.getItems().setAll(values);
        table.setPlaceholder(label("Тут з’являться твої власні слова.","muted"));
        return table;
    }
    private void toast(String message){
        Label l=label(message,"section-title");
        VBox b=card(l);b.setMaxWidth(420);b.setMaxHeight(90);
        StackPane.setAlignment(b,Pos.BOTTOM_CENTER);
        StackPane.setMargin(b,new Insets(0,0,22,0));
        pageHost.getChildren().add(b);
        PauseTransition p=new PauseTransition(Duration.seconds(2));
        p.setOnFinished(e->pageHost.getChildren().remove(b));p.play();
    }

    private void stats(){
        if(round!=null)saveRound();
        Stats s=readStats();VBox page=page();page.getStyleClass().add("stats-page");
        page.getChildren().add(heading("Прогрес","Твоя активність і результати навчання"));
        int mastered=s.levelsMastered().getOrDefault(level,0);
        int total=s.levelsTotal().getOrDefault(level,0);
        VBox growth=card(label("Твій словниковий запас · "+level,"eyebrow"));growth.getStyleClass().add("stats-growth");
        HBox headline=row(8,label(Integer.toString(mastered),"stats-big-number"),label("вивчено із "+total,"muted"));
        growth.getChildren().add(headline);
        try{growth.getChildren().add(growthGraph(db.learnedGrowth(level,28)));}
        catch(SQLException e){error(e);}
        int levelAccuracy=s.accuracy();
        try{levelAccuracy=db.accuracy(level);}catch(SQLException e){error(e);}
        VBox accuracy=card(label("Точність · "+level,"eyebrow"),label(levelAccuracy+"%","stats-metric-value"));
        VBox time=card(label("Час навчання","eyebrow"),label(s.minutes()+" хв","stats-metric-value"),label(s.sessions()+" сесій загалом","muted"));
        VBox right=column(12,accuracy,time);VBox.setVgrow(accuracy,Priority.ALWAYS);VBox.setVgrow(time,Priority.ALWAYS);
        HBox upper=row(12,growth,right);grow(growth);upper.setPrefHeight(198);upper.setMaxHeight(220);
        growth.setPrefWidth(630);right.setPrefWidth(255);HBox.setHgrow(right,Priority.NEVER);
        page.getChildren().add(upper);

        VBox activity=card(row(10,label("Активність за рік","section-title"),flex(),label("Кожен квадрат — один день","muted")));
        activity.getStyleClass().add("heatmap-card");
        HBox heatmap=row(3);heatmap.getStyleClass().add("activity-heatmap");
        LocalDate today=LocalDate.now();
        LocalDate start=today.minusWeeks(52).with(DayOfWeek.MONDAY);
        Map<LocalDate,Integer> count=Map.of();
        try{count=db.activityCounts(start,today);}catch(SQLException e){error(e);}
        for(int week=0;week<53;week++){
            VBox col=new VBox(3);
            for(int day=0;day<7;day++){
                LocalDate date=start.plusDays(week*7L+day);int n=count.getOrDefault(date,0);
                Region cell=new Region();cell.getStyleClass().add("heat-cell");
                cell.setPrefSize(11,11);cell.setMinSize(11,11);
                if(!date.isAfter(today))cell.getStyleClass().add(n==0?"heat-0":n<3?"heat-1":n<7?"heat-2":n<12?"heat-3":"heat-4");
                Tooltip.install(cell,new Tooltip(date+" · "+n+" слів"));col.getChildren().add(cell);
            }
            heatmap.getChildren().add(col);
        }
        activity.getChildren().add(heatmap);page.getChildren().add(activity);
        VBox rank=card(label("Твій статус","eyebrow"),label(Stats.rank(s.mastered()),"rank-highlight","section-title"),
            label("Рівень "+level+"   ·   "+mastered+" / "+total+" опановано","muted"),
            bar(total==0?0:mastered/(double)total));
        int levelDue=s.dueToday();
        try{levelDue=db.dueCount(level);}catch(SQLException e){error(e);}
        VBox revisit=card(label("Час повторити","eyebrow"),label(UkrainianForms.words(levelDue),"stats-metric-value"),
            outline("Відкрити картки  →",()->startRound(View.CARDS)));
        HBox lower=row(12,rank,revisit);grow(rank);grow(revisit);page.getChildren().add(lower);
        render(View.STATS,page);
    }
    /** Render actual cumulative test successes; an empty history is an honest flat line. */
    private Canvas growthGraph(SortedMap<LocalDate,Integer> history){
        Canvas canvas=new Canvas(490,92);
        GraphicsContext g=canvas.getGraphicsContext2D();
        if(history.isEmpty())return canvas;
        double width=490,height=92,pad=9;
        int max=Math.max(1,history.values().stream().mapToInt(Integer::intValue).max().orElse(1));
        int size=history.size();int i=0;
        double[] xs=new double[size],ys=new double[size];
        for(int value:history.values()){
            xs[i]=pad+i*(width-pad*2)/Math.max(1,size-1);
            ys[i]=height-pad-value*(height-pad*2)/max;i++;
        }
        g.setFill(Color.web("#AFBE29",.13));g.beginPath();g.moveTo(xs[0],height-pad);
        for(i=0;i<size;i++)g.lineTo(xs[i],ys[i]);
        g.lineTo(xs[size-1],height-pad);g.closePath();g.fill();
        g.setStroke(Color.web("#B0C20E"));g.setLineWidth(2.5);g.beginPath();g.moveTo(xs[0],ys[0]);
        for(i=1;i<size;i++)g.lineTo(xs[i],ys[i]);g.stroke();
        return canvas;
    }

    private GridPane calendar(YearMonth month,Set<LocalDate> days){
        GridPane grid=new GridPane();grid.setHgap(6);grid.setVgap(6);
        String[] labels={"Пн","Вт","Ср","Чт","Пт","Сб","Нд"};
        for(int col=0;col<7;col++){
            Label l=label(labels[col],"tiny");l.setMinWidth(28);l.setAlignment(Pos.CENTER);
            grid.add(l,col,0);
        }
        int offset=month.atDay(1).getDayOfWeek().getValue()-1;
        for(int day=1;day<=month.lengthOfMonth();day++){
            int idx=offset+day-1;
            boolean active=days.contains(month.atDay(day));
            Label cell=label(Integer.toString(day),"calendar-day",active?"calendar-active":"calendar-empty");
            cell.setMinSize(28,28);cell.setPrefSize(28,28);cell.setAlignment(Pos.CENTER);
            grid.add(cell,idx%7,idx/7+1);
        }
        return grid;
    }
    private VBox metric(String title,String value){
        VBox v=card(label(title,"muted"),label(value,"display-number"));grow(v);return v;
    }

    private <T> HBox segmented(List<T> values,T selected,Consumer<T> onChange){
        HBox group=new HBox(0);group.getStyleClass().add("segmented-group");
        ToggleGroup toggle=new ToggleGroup();
        for(T value:values){
            ToggleButton choice=new ToggleButton(String.valueOf(value));choice.setToggleGroup(toggle);
            choice.getStyleClass().add("segment-choice");choice.setSelected(Objects.equals(value,selected));
            choice.setOnAction(e->onChange.accept(value));group.getChildren().add(choice);
        }
        return group;
    }
    private void exportWords(){
        FileChooser chooser=new FileChooser();chooser.setTitle("Зберегти мої слова");
        chooser.setInitialFileName("englify-my-words.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV-файл","*.csv"));
        var file=chooser.showSaveDialog(stage);if(file==null)return;
        try{
            List<VocabWord> words=db.search("ALL","Усі теми",true,"",100000,0);
            StringBuilder csv=new StringBuilder("\uFEFFEnglish;Українською;Рівень\r\n");
            for(VocabWord w:words)csv.append(csvEscape(w.english())).append(';')
                .append(csvEscape(w.ukrainian())).append(';').append(csvEscape(w.level())).append("\r\n");
            Files.writeString(file.toPath(),csv.toString(),StandardCharsets.UTF_8);
            toast("Збережено "+words.size()+" слів");
        }catch(Exception e){error(e);}
    }
    private String csvEscape(String value){return "\""+value.replace("\"","\"\"")+"\"";}
    private HBox preferenceLine(String title,String subtitle,Node control){
        VBox texts=column(3,label(title,"field-label"),label(subtitle,"muted"));
        HBox line=row(12,texts,flex(),control);line.getStyleClass().add("settings-line");
        return line;
    }
    private void preferences(){
        VBox page=page();page.getChildren().add(heading("Налаштування","Налаштуй Englify під свій темп"));
        VBox panel=column(9);panel.getStyleClass().add("settings-panel");panel.setMaxWidth(880);
        panel.getChildren().add(label("Вигляд","settings-section"));
        ToggleGroup themes=new ToggleGroup();HBox themeButtons=row(9);
        for(String name:List.of("Світла","Темна","Системна")){
            ToggleButton b=new ToggleButton(name);b.setToggleGroup(themes);b.getStyleClass().addAll("setting-choice","theme-preview");
            b.setSelected(settings.theme().equals(name));
            VBox preview=new VBox();preview.getStyleClass().add(name.equals("Світла")?"preview-light":name.equals("Темна")?"preview-dark":"preview-system");
            preview.setMinHeight(28);preview.setPrefHeight(28);preview.setMinWidth(48);
            b.setGraphic(preview);b.setContentDisplay(ContentDisplay.TOP);
            b.setOnAction(e->{setTheme(name);});
            grow(b);themeButtons.getChildren().add(b);
        }
        panel.getChildren().add(preferenceLine("Тема","Вибери вигляд застосунку",themeButtons));
        ToggleGroup fonts=new ToggleGroup();HBox fontButtons=row(5);
        for(String name:List.of("Маленький","Середній","Великий")){
            ToggleButton b=new ToggleButton(name);b.setToggleGroup(fonts);b.getStyleClass().add("setting-choice");
            b.setSelected(settings.fontSize().equals(name));b.setOnAction(e->{settings.fontSize(name);applyFontSize();});
            fontButtons.getChildren().add(b);
        }
        panel.getChildren().add(preferenceLine("Розмір шрифту","Для комфортного читання",fontButtons));
        panel.getChildren().add(label("Навчання","settings-section"));
        HBox goal=segmented(List.of(10,15,20,30),settings.dailyGoal(),settings::dailyGoal);
        HBox count=segmented(List.of(10,15,20,30),settings.words(),settings::words);
        ComboBox<Integer> duration=combo(List.of(0,5,10,15,20,30),settings.minutes());
        duration.setOnAction(e->settings.minutes(duration.getValue()));
        HBox direction=segmented(List.of("EN → UA","UA → EN","Змішано"),
            switch(settings.testDirection()){case "UA_EN"->"UA → EN";case "Змішано"->"Змішано";default->"EN → UA";},
            value->settings.testDirection(value.equals("UA → EN")?"UA_EN":value.equals("Змішано")?"Змішано":"EN_UA"));
        panel.getChildren().addAll(preferenceLine("Денна ціль","Унікальних слів за день",goal),
            preferenceLine("Слів у сесії","Кількість карток",count),
            preferenceLine("Тривалість","Хвилин (0 — без ліміту)",duration),
            preferenceLine("Напрям тестування","Як показувати запитання",direction));
        panel.getChildren().add(label("Гарячі клавіші","settings-section"));
        panel.getChildren().add(label("Space — перегорнути  ·  ← / → — оцінити  ·  1–4 — вибрати відповідь  ·  Enter — далі","muted"));
        panel.getChildren().add(label("Дані","settings-section"));
        panel.getChildren().add(preferenceLine("Експорт моїх слів","CSV із перекладами для Excel або Anki",
            outline("⇩  Експортувати",this::exportWords)));
        panel.getChildren().add(label("Дані зберігаються локально. Джерела словника та ліцензії: docs/SOURCES.md","tiny"));
        page.getChildren().add(panel);render(View.SETTINGS,page);
    }


}
