package com.englify.data;

import com.englify.model.Stats;
import com.englify.model.VocabWord;
import com.englify.model.WordDetails;
import com.englify.learning.SrsScheduler;
import com.englify.learning.SrsScheduler.ReviewState;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Local-first storage. Seed data is read from the packaged dictionary once per installation. */
public final class Database {
    private final String url;

    public Database() throws Exception {
        Path folder = Path.of(System.getProperty("user.home"), ".englify");
        Files.createDirectories(folder);
        url = "jdbc:sqlite:" + folder.resolve("englify.db").toAbsolutePath();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS words (id INTEGER PRIMARY KEY, english TEXT NOT NULL, ukrainian TEXT NOT NULL, level TEXT NOT NULL, topic TEXT NOT NULL, source TEXT NOT NULL, custom INTEGER NOT NULL DEFAULT 0, UNIQUE(english, ukrainian, level))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS saved_words (word_id INTEGER PRIMARY KEY)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS word_metadata (word_id INTEGER PRIMARY KEY, pos TEXT, ipa TEXT, example_en TEXT, example_uk TEXT, audio_path TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            s.executeUpdate("INSERT INTO schema_version(version) SELECT 3 WHERE NOT EXISTS (SELECT 1 FROM schema_version)");
            s.executeUpdate("UPDATE schema_version SET version=3 WHERE version<3");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS attempts (id INTEGER PRIMARY KEY, word_id INTEGER NOT NULL, correct INTEGER NOT NULL, day TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS activity (id INTEGER PRIMARY KEY, word_id INTEGER NOT NULL, kind TEXT NOT NULL, day TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sessions (id INTEGER PRIMARY KEY, started_at TEXT NOT NULL, day TEXT NOT NULL, mode TEXT NOT NULL, words INTEGER NOT NULL, seconds INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS review_state (word_id INTEGER PRIMARY KEY, due_on TEXT NOT NULL, interval_days INTEGER NOT NULL DEFAULT 0, ease REAL NOT NULL DEFAULT 2.5, repetitions INTEGER NOT NULL DEFAULT 0, lapses INTEGER NOT NULL DEFAULT 0)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_review_due ON review_state(due_on)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_words_level ON words(level, topic)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_attempts_word ON attempts(word_id)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_activity_day ON activity(day)");
        }
        seed();
        seedExamples();
        seedPronunciations();
    }

    private void seedPronunciations() throws Exception {
        InputStream in=getClass().getResourceAsStream("/com/englify/pronunciation_ipa.tsv");
        if(in==null)return;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
            Connection c=connection()){
            c.setAutoCommit(false);
            try(PreparedStatement insert=c.prepareStatement(
                    "INSERT OR IGNORE INTO word_metadata(word_id,pos,ipa,example_en,example_uk) " +
                    "SELECT id,'',?, '', '' FROM words WHERE lower(english)=?");
                PreparedStatement update=c.prepareStatement(
                    "UPDATE word_metadata SET ipa=? WHERE (ipa IS NULL OR ipa='') " +
                    "AND word_id IN (SELECT id FROM words WHERE lower(english)=?)")){
                String line;
                while((line=reader.readLine())!=null){
                    if(line.isBlank()||line.startsWith("#"))continue;
                    String[] fields=line.split("\t",-1);
                    if(fields.length!=2)continue;
                    insert.setString(1,fields[1]);insert.setString(2,fields[0]);insert.addBatch();
                    update.setString(1,fields[1]);update.setString(2,fields[0]);update.addBatch();
                }
                insert.executeBatch();update.executeBatch();c.commit();
            }
        }
    }

    private Connection connection() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=4000");
        }
        return c;
    }

    private void seed() throws Exception {
        InputStream in = getClass().getResourceAsStream("/com/englify/words.tsv");
        if (in == null) throw new IllegalStateException("Не знайдено вбудований словник words.tsv");
        try (InputStream source = in; BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
             Connection c = connection(); PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO words(english,ukrainian,level,topic,source,custom) VALUES (?,?,?,?,?,0)")) {
            c.setAutoCommit(false);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("English\t")) continue;
                String[] f = line.split("\\t", -1);
                if (f.length != 5) throw new IllegalStateException("Помилка словника: " + line);
                for (int i = 0; i < f.length; i++) p.setString(i + 1, f[i]);
                p.addBatch();
            }
            p.executeBatch();
            c.commit();
        }
    }

    /** Seed/update example sentences non-destructively, preserving personal progress and existing IPA. */
    private void seedExamples() throws Exception {
        InputStream in=getClass().getResourceAsStream("/com/englify/examples.tsv");
        if(in==null)return;
        try(BufferedReader lines=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
            Connection c=connection();
            PreparedStatement insert=c.prepareStatement(
                "INSERT OR IGNORE INTO word_metadata(word_id,pos,ipa,example_en,example_uk) " +
                "SELECT id,?,?,?,? FROM words WHERE english=? AND level=? LIMIT 1");
            PreparedStatement update=c.prepareStatement(
                "UPDATE word_metadata SET pos=CASE WHEN pos IS NULL OR pos='' THEN ? ELSE pos END, " +
                "ipa=CASE WHEN ipa IS NULL OR ipa='' THEN ? ELSE ipa END, " +
                "example_en=CASE WHEN ?<>'' THEN ? ELSE example_en END, " +
                "example_uk=CASE WHEN ?<>'' THEN ? ELSE example_uk END " +
                "WHERE word_id IN (SELECT id FROM words WHERE english=? AND level=?)")) {
            c.setAutoCommit(false);
            String line;
            while((line=lines.readLine())!=null){
                if(line.isBlank()||line.startsWith("#"))continue;
                String[] f=line.split("\t",-1);
                if(f.length!=6)throw new IllegalStateException("Invalid examples.tsv line: "+line);
                insert.setString(1,f[2]);insert.setString(2,f[3]);insert.setString(3,f[4]);
                insert.setString(4,f[5]);insert.setString(5,f[0]);insert.setString(6,f[1]);insert.addBatch();
                update.setString(1,f[2]);update.setString(2,f[3]);update.setString(3,f[4]);
                update.setString(4,f[4]);update.setString(5,f[5]);update.setString(6,f[5]);
                update.setString(7,f[0]);update.setString(8,f[1]);update.addBatch();
            }
            insert.executeBatch();update.executeBatch();c.commit();
        }
    }

    public Optional<VocabWord> wordOfDay(String level) throws SQLException {
        // Only display a word of the day when it has a real bilingual usage example.
        String sql="SELECT w.* FROM words w JOIN word_metadata m ON m.word_id=w.id " +
            "WHERE w.level=? AND length(trim(COALESCE(m.example_en,'')))>0 " +
            "AND length(trim(COALESCE(m.example_uk,'')))>0 ORDER BY w.english";
        List<VocabWord> candidates=new ArrayList<>();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,level);try(ResultSet r=p.executeQuery()){
                while(r.next())candidates.add(word(r));
            }
        }
        if(candidates.isEmpty())return Optional.empty();
        return Optional.of(candidates.get(Math.floorMod(LocalDate.now().toEpochDay(), candidates.size())));
    }
    /** Review action from the dashboard must not silently mix in unseen words. */
    public List<VocabWord> dueWords(String level,int max) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        String sql="SELECT w.* FROM review_state r JOIN words w ON w.id=r.word_id " +
                "WHERE w.level=? AND r.due_on<=? ORDER BY r.due_on ASC,w.english LIMIT ?";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,level);p.setString(2,LocalDate.now().toString());p.setInt(3,max);
            try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        return result;
    }
    public int accuracy(String level) throws SQLException {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(
            "SELECT COUNT(*),COALESCE(SUM(a.correct),0) FROM attempts a JOIN words w ON w.id=a.word_id WHERE w.level=?")){
            p.setString(1,level);
            try(ResultSet r=p.executeQuery()){
                if(r.next())return r.getInt(1)==0?0:(int)Math.round(r.getInt(2)*100.0/r.getInt(1));
            }
        }
        return 0;
    }
    public List<VocabWord> unseenWords(String level,int max) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        String sql="SELECT w.* FROM words w WHERE w.level=? AND NOT EXISTS " +
            "(SELECT 1 FROM activity a WHERE a.word_id=w.id AND a.kind='flashcard') ORDER BY RANDOM() LIMIT ?";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,level);p.setInt(2,max);
            try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        return result;
    }
    /** A new word is one whose first flashcard encounter was today, not every old word reviewed today. */
    public Set<Long> firstSeenTodayIds() throws SQLException {
        Set<Long> ids=new HashSet<>();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(
            "SELECT word_id FROM activity WHERE kind='flashcard' GROUP BY word_id HAVING MIN(day)=?")){
            p.setString(1,LocalDate.now().toString());
            try(ResultSet r=p.executeQuery()){while(r.next())ids.add(r.getLong(1));}
        }
        return ids;
    }
    public Map<LocalDate,Integer> activityCounts(LocalDate from,LocalDate to) throws SQLException {
        Map<LocalDate,Integer> counts=new TreeMap<>();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(
            "SELECT day,COUNT(DISTINCT word_id) FROM activity WHERE day>=? AND day<=? GROUP BY day")){
            p.setString(1,from.toString());p.setString(2,to.toString());
            try(ResultSet r=p.executeQuery()){
                while(r.next())counts.put(LocalDate.parse(r.getString(1)),r.getInt(2));
            }
        }
        return counts;
    }
    /** Number of distinct vocabulary items whose latest test is correct, per day (cumulative). */
    public SortedMap<LocalDate,Integer> learnedGrowth(String selectedLevel,int days) throws SQLException {
        LocalDate from=LocalDate.now().minusDays(days-1L);
        Map<LocalDate,Integer> firstCorrect=new TreeMap<>();
        String sql="SELECT MIN(a.day) AS first_day,COUNT(*) FROM (SELECT word_id,MIN(day) AS day " +
            "FROM attempts WHERE correct=1 GROUP BY word_id) a JOIN words w ON w.id=a.word_id " +
            "WHERE w.level=? GROUP BY a.day";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,selectedLevel);
            try(ResultSet r=p.executeQuery()){
                while(r.next())firstCorrect.put(LocalDate.parse(r.getString(1)),r.getInt(2));
            }
        }
        SortedMap<LocalDate,Integer> cumulative=new TreeMap<>();
        int sum=0;
        for(Map.Entry<LocalDate,Integer> entry:firstCorrect.entrySet())if(entry.getKey().isBefore(from))sum+=entry.getValue();
        for(int i=0;i<days;i++){
            LocalDate day=from.plusDays(i);sum+=firstCorrect.getOrDefault(day,0);cumulative.put(day,sum);
        }
        return cumulative;
    }

    public Optional<WordDetails> details(long id) throws SQLException {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM word_metadata WHERE word_id=?")) {
            p.setLong(1,id);try(ResultSet r=p.executeQuery()){
                if(!r.next())return Optional.empty();
                return Optional.of(new WordDetails(r.getString("pos"),r.getString("ipa"),r.getString("example_en"),r.getString("example_uk"),r.getString("audio_path")));
            }
        }
    }
    private VocabWord word(ResultSet r) throws SQLException {
        return new VocabWord(r.getLong("id"),r.getString("english"),r.getString("ukrainian"),
                r.getString("level"),r.getString("topic"),r.getString("source"),r.getInt("custom") == 1);
    }

    public int count(String level) throws SQLException {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM words WHERE (?='ALL' OR level=?)")) {
            p.setString(1,level); p.setString(2,level);
            try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
        }
    }

    public List<String> topics(String level) throws SQLException {
        List<String> list = new ArrayList<>(); list.add("Усі теми");
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT DISTINCT topic FROM words WHERE (?='ALL' OR level=?) ORDER BY topic")) {
            p.setString(1,level);p.setString(2,level);
            try(ResultSet r=p.executeQuery()){while(r.next())list.add(r.getString(1));}
        }
        return list;
    }

    private String whereSql() {
        return " WHERE (?='ALL' OR level=?) AND (?='Усі теми' OR topic=?) AND (?=0 OR EXISTS (SELECT 1 FROM saved_words sw WHERE sw.word_id=words.id)) " +
               "AND (?='' OR lower(english) LIKE '%'||lower(?)||'%' OR lower(ukrainian) LIKE '%'||lower(?)||'%')";
    }
    private void searchArgs(PreparedStatement p, String level,String topic,boolean custom,String term) throws SQLException {
        p.setString(1,level);p.setString(2,level);p.setString(3,topic);p.setString(4,topic);
        p.setInt(5,custom?1:0);p.setString(6,term);p.setString(7,term);p.setString(8,term);
    }
    public int countSearch(String level,String topic,boolean custom,String term) throws SQLException {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM words"+whereSql())) {
            searchArgs(p,level,topic,custom,term);
            try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
        }
    }
    public List<VocabWord> search(String level,String topic,boolean custom,String term,int limit,int offset) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM words"+whereSql()+" ORDER BY english COLLATE NOCASE LIMIT ? OFFSET ?")) {
            searchArgs(p,level,topic,custom,term);
            p.setInt(9,limit);p.setInt(10,offset);
            try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        return result;
    }
    /** UI-only lexical type filter; a temporary heuristic until words are tagged manually. */
    public int countSearchKind(String level,String topic,boolean custom,String term,String kind) throws SQLException {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM words"+whereSql()+
                " AND (?='Усі типи' OR (?='Слова' AND instr(trim(english),' ')=0) OR (?='Фрази' AND instr(trim(english),' ')>0))")) {
            searchArgs(p,level,topic,custom,term);p.setString(9,kind);p.setString(10,kind);p.setString(11,kind);
            try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
        }
    }
    public List<VocabWord> searchKind(String level,String topic,boolean custom,String term,String kind,int limit,int offset) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM words"+whereSql()+
                " AND (?='Усі типи' OR (?='Слова' AND instr(trim(english),' ')=0) OR (?='Фрази' AND instr(trim(english),' ')>0)) ORDER BY english COLLATE NOCASE LIMIT ? OFFSET ?")) {
            searchArgs(p,level,topic,custom,term);
            p.setString(9,kind);p.setString(10,kind);p.setString(11,kind);p.setInt(12,limit);p.setInt(13,offset);
            try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        return result;
    }
    public List<VocabWord> studyWords(String level,String topic,int count) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        // Due reviews first; then genuinely unseen words; only then non-due reviewed cards.
        String sql="SELECT w.* FROM words w LEFT JOIN review_state r ON r.word_id=w.id " +
                "WHERE w.level=? AND (?='Усі теми' OR w.topic=?) " +
                "ORDER BY CASE WHEN r.word_id IS NOT NULL AND r.due_on<=? THEN 0 " +
                "WHEN r.word_id IS NULL THEN 1 ELSE 2 END, RANDOM() LIMIT ?";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,level);p.setString(2,topic);p.setString(3,topic);
            p.setString(4,LocalDate.now().toString());p.setInt(5,count);
            try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        return result;
    }
    public List<VocabWord> distractors(String level,long excludingId,int max) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM words WHERE level=? AND id<>? ORDER BY RANDOM() LIMIT ?")) {
            p.setString(1,level);p.setLong(2,excludingId);p.setInt(3,max);
            try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        return result;
    }
    public Optional<VocabWord> findKnown(String typed) throws SQLException {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM words WHERE lower(english)=lower(?) OR lower(ukrainian)=lower(?) ORDER BY custom DESC LIMIT 1")) {
            p.setString(1,typed.trim());p.setString(2,typed.trim());
            try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(word(r)):Optional.empty();}
        }
    }
    public void addCustom(String english,String ukrainian,String level) throws SQLException {
        String en=english.trim(),uk=ukrainian.trim();
        if(en.isBlank()||uk.isBlank())throw new IllegalArgumentException("Потрібні і слово, і переклад.");
        if(en.length()>120||uk.length()>220)throw new IllegalArgumentException("Занадто довгий текст слова.");
        try(Connection c=connection()) {
            c.setAutoCommit(false);
            long id = -1;
            try(PreparedStatement p=c.prepareStatement("SELECT id FROM words WHERE lower(english)=lower(?) AND lower(ukrainian)=lower(?) AND level=? LIMIT 1")) {
                p.setString(1,en);p.setString(2,uk);p.setString(3,level);
                try(ResultSet r=p.executeQuery()){if(r.next())id=r.getLong(1);}
            }
            if(id<0){
                try(PreparedStatement p=c.prepareStatement("INSERT INTO words(english,ukrainian,level,topic,source,custom) VALUES (?,?,?,'Мої слова','Додано користувачем',1)")) {
                    p.setString(1,en);p.setString(2,uk);p.setString(3,level);p.executeUpdate();
                }
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT last_insert_rowid()")){
                    if(r.next())id=r.getLong(1);
                }
            }
            try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO saved_words(word_id) VALUES (?)")) {
                p.setLong(1,id);p.executeUpdate();
            }
            c.commit();
        }
    }
    public void deleteCustom(long id) throws SQLException {
        try(Connection c=connection()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("DELETE FROM saved_words WHERE word_id=?")) {
                p.setLong(1,id);p.executeUpdate();
            }
            int custom=0;
            try(PreparedStatement p=c.prepareStatement("SELECT custom FROM words WHERE id=?")){
                p.setLong(1,id);try(ResultSet r=p.executeQuery()){if(r.next())custom=r.getInt(1);}
            }
            if(custom==1){
                try(PreparedStatement details=c.prepareStatement("DELETE FROM word_metadata WHERE word_id=?")) {
                    details.setLong(1,id);details.executeUpdate();
                }
                try(PreparedStatement review=c.prepareStatement("DELETE FROM review_state WHERE word_id=?")) {
                    review.setLong(1,id);review.executeUpdate();
                }
                try(PreparedStatement a=c.prepareStatement("DELETE FROM attempts WHERE word_id=?");
                    PreparedStatement b=c.prepareStatement("DELETE FROM activity WHERE word_id=?");
                    PreparedStatement w=c.prepareStatement("DELETE FROM words WHERE id=? AND custom=1")) {
                    a.setLong(1,id);a.executeUpdate();b.setLong(1,id);b.executeUpdate();w.setLong(1,id);w.executeUpdate();
                }
            }
            c.commit();
        }
    }
    public void saveWord(long wordId) throws SQLException {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO saved_words(word_id) VALUES (?)")) {
            p.setLong(1,wordId);p.executeUpdate();
        }
    }
    public int learningCount(String level) throws SQLException {
        String sql="SELECT COUNT(*) FROM review_state r JOIN words w ON w.id=r.word_id " +
                "WHERE w.level=? AND r.word_id NOT IN (SELECT a.word_id FROM attempts a WHERE a.correct=1 AND a.id=(SELECT MAX(id) FROM attempts b WHERE b.word_id=a.word_id))";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,level);
            try(ResultSet rs=p.executeQuery()){return rs.next()?rs.getInt(1):0;}
        }
    }
    public int dueCount(String level) throws SQLException {
        String sql="SELECT COUNT(*) FROM review_state r JOIN words w ON w.id=r.word_id " +
                "WHERE (?='ALL' OR w.level=?) AND r.due_on<=?";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,level);p.setString(2,level);p.setString(3,LocalDate.now().toString());
            try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
        }
    }
    public List<VocabWord> recentMistakes(int limit) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        String sql="SELECT w.* FROM words w JOIN (SELECT word_id,MAX(id) last_id FROM attempts WHERE correct=0 GROUP BY word_id) a ON a.word_id=w.id ORDER BY a.last_id DESC LIMIT ?";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)) {
            p.setInt(1,limit);try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        return result;
    }
    public List<VocabWord> wordsByIds(List<Long> ids) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        if(ids.isEmpty())return result;
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM words WHERE id IN ("+placeholders+")")) {
            for(int i=0;i<ids.size();i++)p.setLong(i+1,ids.get(i));
            try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        result.sort(Comparator.comparingInt(w->ids.indexOf(w.id())));
        return result;
    }
    public List<VocabWord> distractorsLike(VocabWord target, int limit) throws SQLException {
        List<VocabWord> candidates=distractors(target.level(),target.id(),450);
        boolean targetPhrase=target.english().trim().contains(" ");
        int len=target.ukrainian().length();
        candidates.removeIf(w -> w.english().trim().contains(" ")!=targetPhrase);
        // Until part-of-speech annotations are available, favor similar answer length.
        candidates.sort(Comparator.comparingInt(w->Math.abs(w.ukrainian().length()-len)));
        return candidates.subList(0, Math.min(limit,candidates.size()));
    }
    /** Personal dictionary includes words that were actually flipped or answered. */
    public int countSeen(String term,String kind) throws SQLException {
        String sql="SELECT COUNT(*) FROM words w WHERE EXISTS (SELECT 1 FROM activity a WHERE a.word_id=w.id AND a.kind='flashcard') " +
            "AND (?='' OR lower(w.english) LIKE '%'||lower(?)||'%' OR lower(w.ukrainian) LIKE '%'||lower(?)||'%') " +
            "AND (?='Усі типи' OR (?='Слова' AND instr(trim(w.english),' ')=0) OR (?='Фрази' AND instr(trim(w.english),' ')>0))";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,term);p.setString(2,term);p.setString(3,term);
            p.setString(4,kind);p.setString(5,kind);p.setString(6,kind);
            try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
        }
    }
    public List<VocabWord> seenWords(String term,String kind,int limit,int offset) throws SQLException {
        List<VocabWord> result=new ArrayList<>();
        String sql="SELECT w.* FROM words w WHERE EXISTS (SELECT 1 FROM activity a WHERE a.word_id=w.id AND a.kind='flashcard') " +
            "AND (?='' OR lower(w.english) LIKE '%'||lower(?)||'%' OR lower(w.ukrainian) LIKE '%'||lower(?)||'%') " +
            "AND (?='Усі типи' OR (?='Слова' AND instr(trim(w.english),' ')=0) OR (?='Фрази' AND instr(trim(w.english),' ')>0)) " +
            "ORDER BY (SELECT MAX(a.id) FROM activity a WHERE a.word_id=w.id) DESC LIMIT ? OFFSET ?";
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,term);p.setString(2,term);p.setString(3,term);
            p.setString(4,kind);p.setString(5,kind);p.setString(6,kind);
            p.setInt(7,limit);p.setInt(8,offset);
            try(ResultSet r=p.executeQuery()){while(r.next())result.add(word(r));}
        }
        return result;
    }
    public Set<LocalDate> activityDays(java.time.YearMonth month) throws SQLException {
        Set<LocalDate> days=new HashSet<>();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(
                "SELECT DISTINCT day FROM activity WHERE day>=? AND day<?")){
            p.setString(1,month.atDay(1).toString());p.setString(2,month.plusMonths(1).atDay(1).toString());
            try(ResultSet r=p.executeQuery()){while(r.next())days.add(LocalDate.parse(r.getString(1)));}
        }
        return days;
    }
    public Set<Long> reviewedWordIds() throws SQLException {
        Set<Long> ids=new HashSet<>();
        try(Connection c=connection();Statement s=c.createStatement();ResultSet rs=s.executeQuery(
                "SELECT word_id FROM review_state")){
            while(rs.next())ids.add(rs.getLong(1));
        }
        return ids;
    }
    public Set<Long> masteredWordIds() throws SQLException {
        Set<Long> ids=new HashSet<>();
        try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery(
                "SELECT a.word_id FROM attempts a WHERE a.correct=1 AND a.id=(SELECT MAX(id) FROM attempts b WHERE b.word_id=a.word_id)")){
            while(r.next())ids.add(r.getLong(1));
        }
        return ids;
    }
    public void recordFlip(long wordId) throws SQLException { recordActivity(wordId,"flashcard"); }
    private void recordActivity(long id,String kind) throws SQLException {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT INTO activity(word_id,kind,day) VALUES(?,?,?)")) {
            p.setLong(1,id);p.setString(2,kind);p.setString(3,LocalDate.now().toString());p.executeUpdate();
        }
    }
    public void recordCardReview(long id,boolean recalled) throws SQLException {
        try(Connection c=connection()) {
            c.setAutoCommit(false);
            applyReview(c,id,recalled);
            try(PreparedStatement p=c.prepareStatement("INSERT INTO activity(word_id,kind,day) VALUES(?,'flashcard',?)")) {
                p.setLong(1,id);p.setString(2,LocalDate.now().toString());p.executeUpdate();
            }
            c.commit();
        }
    }
    private void applyReview(Connection c,long id,boolean recalled) throws SQLException {
        ReviewState previous=null;
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM review_state WHERE word_id=?")) {
            p.setLong(1,id);
            try(ResultSet r=p.executeQuery()) {
                if(r.next()) previous=new ReviewState(r.getInt("repetitions"),r.getInt("interval_days"),r.getDouble("ease"),r.getInt("lapses"),LocalDate.parse(r.getString("due_on")));
            }
        }
        ReviewState next=SrsScheduler.review(previous,recalled,LocalDate.now());
        try(PreparedStatement p=c.prepareStatement("INSERT INTO review_state(word_id,due_on,interval_days,ease,repetitions,lapses) VALUES(?,?,?,?,?,?) ON CONFLICT(word_id) DO UPDATE SET due_on=excluded.due_on,interval_days=excluded.interval_days,ease=excluded.ease,repetitions=excluded.repetitions,lapses=excluded.lapses")) {
            p.setLong(1,id);p.setString(2,next.dueOn().toString());p.setInt(3,next.intervalDays());p.setDouble(4,next.ease());p.setInt(5,next.repetitions());p.setInt(6,next.lapses());p.executeUpdate();
        }
    }
    public void recordAnswer(long id,boolean correct) throws SQLException {
        try(Connection c=connection()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("INSERT INTO attempts(word_id,correct,day) VALUES(?,?,?)");
                PreparedStatement a=c.prepareStatement("INSERT INTO activity(word_id,kind,day) VALUES(?,'quiz',?)")) {
                p.setLong(1,id);p.setInt(2,correct?1:0);p.setString(3,LocalDate.now().toString());p.executeUpdate();
                a.setLong(1,id);a.setString(2,LocalDate.now().toString());a.executeUpdate();
            }
            applyReview(c,id,correct);
            c.commit();
        }
    }
    public void recordSession(LocalDateTime started,String mode,int count,int seconds) throws SQLException {
        if(count==0)return;
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT INTO sessions(started_at,day,mode,words,seconds) VALUES(?,?,?,?,?)")) {
            p.setString(1,started.toString());p.setString(2,started.toLocalDate().toString());p.setString(3,mode);
            p.setInt(4,count);p.setInt(5,Math.max(0,seconds));p.executeUpdate();
        }
    }
    public Stats stats() throws SQLException {
        int total=0,correct=0,today=0,days=0,streak=0,sessions=0,seconds=0,mastered=0,due=0;
        Map<String,Integer> totals=new LinkedHashMap<>(),learned=new LinkedHashMap<>();
        for(String l:List.of("A1","A2","B1")){totals.put(l,0);learned.put(l,0);}
        Map<LocalDate,Integer> week=new LinkedHashMap<>();
        LocalDate monday=LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        for(int i=0;i<7;i++)week.put(monday.plusDays(i),0);
        SortedSet<LocalDate> activeDays=new TreeSet<>();
        try(Connection c=connection();Statement s=c.createStatement()) {
            try(ResultSet r=s.executeQuery("SELECT COUNT(*),COALESCE(SUM(correct),0) FROM attempts")) {
                if(r.next()){total=r.getInt(1);correct=r.getInt(2);}
            }
            try(ResultSet r=s.executeQuery("SELECT w.level,COUNT(*),COALESCE(SUM(CASE WHEN a.correct=1 THEN 1 ELSE 0 END),0) " +
                    "FROM words w LEFT JOIN (SELECT t.word_id,t.correct FROM attempts t WHERE t.id=(SELECT MAX(id) FROM attempts z WHERE z.word_id=t.word_id)) a ON a.word_id=w.id GROUP BY w.level")){
                while(r.next()) {totals.put(r.getString(1),r.getInt(2));learned.put(r.getString(1),r.getInt(3));mastered+=r.getInt(3);}
            }
            try(ResultSet r=s.executeQuery("SELECT day,COUNT(DISTINCT word_id) FROM activity GROUP BY day")){
                while(r.next()){
                    LocalDate date=LocalDate.parse(r.getString(1));activeDays.add(date);
                    if(date.equals(LocalDate.now()))today=r.getInt(2);
                    if(week.containsKey(date))week.put(date,r.getInt(2));
                }
            }
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM review_state WHERE due_on<=date('now','localtime')")) {
                if(r.next())due=r.getInt(1);
            }
            days=activeDays.size();
            LocalDate check=LocalDate.now();
            if(!activeDays.contains(check))check=check.minusDays(1);
            while(activeDays.contains(check)){streak++;check=check.minusDays(1);}
            try(ResultSet r=s.executeQuery("SELECT COUNT(*),COALESCE(SUM(seconds),0) FROM sessions")){
                if(r.next()){sessions=r.getInt(1);seconds=r.getInt(2);}
            }
        }
        return new Stats(today,mastered,total,correct,days,streak,sessions,seconds/60,due,totals,learned,week);
    }
}
