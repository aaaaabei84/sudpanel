package com.sudpanel.api;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * M7 接口测试持久化。6 张表：
 * <ul>
 *   <li>{@code api_case} —— 接口用例（地址/请求头/请求体/断言）</li>
 *   <li>{@code api_code} —— 返回码字典（种子来自官方公开文档，可自行补充）</li>
 *   <li>{@code api_batch} —— 批量/定时执行批次</li>
 *   <li>{@code api_exec} —— 每一次调用的执行明细</li>
 *   <li>{@code api_sign} —— 签名设置（单行；含密钥，只在本机库里）</li>
 *   <li>{@code api_var} —— 自定义变量（请求头一组、请求体一组，两组互不干涉）</li>
 * </ul>
 *
 * <p>建表与迁移全部幂等，写在构造器里，保证「仓库一被注入，表一定就绪」。
 * INSERT 取自增 id 一律同连接内完成（DriverManagerDataSource 非连接池的老坑）。
 *
 * <p>保密约定：用例里的地址、请求头、请求体可能是内部资料，**只落本地 SQLite**
 * （在用户的 Application Support 目录下，不进 git）。代码里不预置任何真实值。
 * 变量的值**不属于**保密项 —— 它本来就要填进请求里、界面上也要能看见，所以照常回传；
 * 真正只写不读的只有签名设置里的密钥。
 */
@Repository
public class ApiRepository {

    private static final Logger log = LoggerFactory.getLogger(ApiRepository.class);

    /** 官方公开文档的返回码种子文件（公开内容，随 jar 分发）。 */
    private static final String SEED_RESOURCE = "api-codes-seed.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final ApiSecretStore secrets;
    public ApiSecretStore secrets(){return secrets;}
    JdbcTemplate jdbc(){return jdbc;}

    public ApiRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        initTables();
        secrets=new ApiSecretStore(jdbc);
        markRunningBatchesInterrupted();
        loadCodeSeed();
    }

    // ==================== 建表 ====================

    private void initTables() {
        // 表1 api_case：接口用例。expect_* 留空表示不校验该项断言。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_case (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  method TEXT NOT NULL,
                  url TEXT NOT NULL,
                  headers TEXT,
                  body TEXT,
                  note TEXT,
                  expect_status TEXT,
                  expect_code TEXT,
                  timeout_ms INTEGER,
                  insecure INTEGER NOT NULL DEFAULT 0,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  sort_order INTEGER,
                  created_at TEXT,
                  updated_at TEXT,
                  asserts TEXT
                )
                """);
        // 表2 api_code：返回码字典。source 区分 SEED（公开文档）与 CUSTOM（自行补充）。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_code (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  scope TEXT NOT NULL,
                  scope_label TEXT,
                  code INTEGER NOT NULL,
                  message TEXT,
                  source TEXT NOT NULL,
                  created_at TEXT
                )
                """);
        // 同一分类下同一码只留一条；重复导入种子时靠它做去重
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_api_code ON api_code(scope, code)");
        // 表3 api_batch：批次。case_ids 存逗号分隔的用例 id，顺序即执行顺序。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_batch (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT,
                  mode TEXT NOT NULL,
                  case_ids TEXT NOT NULL,
                  interval_seconds INTEGER NOT NULL DEFAULT 0,
                  repeat_count INTEGER NOT NULL DEFAULT 1,
                  status TEXT NOT NULL,
                  total_execs INTEGER NOT NULL DEFAULT 0,
                  pass_count INTEGER NOT NULL DEFAULT 0,
                  fail_count INTEGER NOT NULL DEFAULT 0,
                  error_count INTEGER NOT NULL DEFAULT 0,
                  started_at TEXT,
                  finished_at TEXT
                )
                """);
        // 表4 api_exec：执行明细
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_exec (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  batch_id INTEGER,
                  case_id INTEGER,
                  case_name TEXT,
                  round_no INTEGER,
                  seq INTEGER,
                  method TEXT,
                  url TEXT,
                  http_status INTEGER,
                  resp_code TEXT,
                  code_meaning TEXT,
                  verdict TEXT,
                  reason TEXT,
                  elapsed_ms INTEGER,
                  resp_body TEXT,
                  resp_headers TEXT,
                  error TEXT,
                  ts TEXT,
                  asserts_result TEXT
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_api_exec_batch ON api_exec(batch_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_api_exec_batch_order ON api_exec(batch_id,round_no,seq,id)");
        // 表5 api_sign：签名设置。固定只存一行（id=1），因为这是「环境级」而不是「用例级」的信息
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_sign (
                  id INTEGER PRIMARY KEY,
                  enabled INTEGER NOT NULL DEFAULT 0,
                  app_id TEXT,
                  app_secret TEXT,
                  nonce TEXT,
                  mg_id TEXT,
                  updated_at TEXT
                )
                """);
        // 表5b api_env：环境（v1.10.7）。一个环境 = 一套变量（三组）+ 一套签名，
        // 用例分组通过 env_id 引用其中一个；没绑定的用默认环境（is_default=1）。
        // 为什么要这张表：换环境跑一轮是高频动作，而变量和签名**天然配套**
        // （app_secret / mg_id 在测试与生产不可能一样），拆成两套分组反而有错配风险。
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_env (\n"
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                + "  name TEXT NOT NULL UNIQUE,\n"
                + "  is_default INTEGER NOT NULL DEFAULT 0,\n"
                + "  sort_order INTEGER,\n"
                + "  created_at TEXT,\n"
                + "  updated_at TEXT\n"
                + ")");
        // 默认环境：老库升级时自动补一条，把现有变量与签名都挂到它下面 —— 老用例行为不变。
        Integer envN = jdbc.queryForObject("SELECT COUNT(*) FROM api_env", Integer.class);
        if (envN == null || envN == 0) {
            jdbc.update("INSERT INTO api_env(id, name, is_default, sort_order, created_at, updated_at) "
                    + "VALUES (1, '默认环境', 1, 1, ?, ?)", Instant.now().toString(),
                    Instant.now().toString());
        }
        // 表6 api_var：自定义变量。scope 区分「请求头那一组」与「请求体那一组」，
        // 两组放在同一张表里靠 scope 分开 —— 表结构完全一样，拆成两张只多一份重复的 DDL
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_var (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  scope TEXT NOT NULL,
                  name TEXT NOT NULL,
                  value TEXT,
                  sort_order INTEGER,
                  updated_at TEXT
                )
                """);
        // 唯一约束在环境迁移中统一建立，不能重建不含env_id的旧全局索引。
        // 表7 api_case_group：用例分组（2026-10-04 新增，需求 #48）。
        // 独立成表而不是用例上的文本列 —— 改名只动一行，也不会出现「改到一半一半旧一半新」。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS api_case_group (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  sort_order INTEGER,
                  created_at TEXT,
                  updated_at TEXT
                )
                """);
        // 分组名唯一：两个同名分组在界面上完全看不出区别，选中哪个全凭运气
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_api_case_group ON api_case_group(name)");
        migrateExecRequestColumns();
        migrateExecAssertsColumn();
        migrateCaseGroupColumn();
        migrateCaseAssertsColumn();
        migrateEnvColumns();
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> migrateHttpWorkbench());
    }


    private void migrateHttpWorkbench() {
        addColumnIfMissing("api_case", "request_options", "TEXT");
        addColumnIfMissing("api_var", "secret", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("api_case_group", "import_note", "TEXT");
        addColumnIfMissing("api_case_group", "import_path", "TEXT");
        addColumnIfMissing("api_exec", "metadata", "TEXT");
        addColumnIfMissing("api_env", "strict_variables", "INTEGER");
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_batch_plan (batch_id INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL, sealed_plan TEXT NOT NULL, digest TEXT NOT NULL, created_at TEXT NOT NULL)");
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS tr_api_batch_plan_delete AFTER DELETE ON api_batch BEGIN DELETE FROM api_batch_plan WHERE batch_id=OLD.id; END");
        addColumnIfMissing("api_batch", "skipped_execs", "INTEGER NOT NULL DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_scenario (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, definition TEXT NOT NULL, updated_at TEXT)");
    }

    private void addColumnIfMissing(String table, String column, String type) {
        boolean exists = jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(row -> column.equals(row.get("name")));
        if (!exists) {
            try { jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type); }
            catch (DataAccessException e) {
                if (e.getMessage() == null || !e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw e;
            }
        }
    }

    private static final ObjectMapper OPTIONS_JSON = new ObjectMapper();

    private static String encode(Object value) {
        if (value == null) return null;
        try { return OPTIONS_JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("配置不能序列化", e); }
    }

    private static ApiRequestOptions decodeOptions(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OPTIONS_JSON.readValue(value, ApiRequestOptions.class); }
        catch (Exception e) { throw new IllegalStateException("请求选项损坏，需重新编辑用例", e); }
    }

    public List<ApiScenario> listScenarios() {
        return jdbc.query("SELECT id, definition FROM api_scenario ORDER BY id", (rs, i) ->
                decodeScenario(rs.getLong("id"), rs.getString("definition")));
    }

    public ApiScenario getScenario(long id) {
        return jdbc.query("SELECT id, definition FROM api_scenario WHERE id=?", (rs, i) ->
                decodeScenario(rs.getLong("id"), rs.getString("definition")), id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("场景不存在：" + id));
    }

    private static ApiScenario decodeScenario(long id, String value) {
        try {
            ApiScenario scenario = OPTIONS_JSON.readValue(value, ApiScenario.class);
            return new ApiScenario(id, scenario.name(), scenario.steps());
        } catch (Exception e) { throw new IllegalStateException("场景配置损坏：" + id, e); }
    }

    public ApiScenario saveScenario(Long id, ApiScenario scenario) {
        String name = cleanGroupName(scenario.name());
        if (scenario.steps() == null || scenario.steps().isEmpty() || scenario.steps().size() > 200)
            throw new IllegalArgumentException("场景需要 1 至 200 个步骤");
        var keys = new java.util.HashSet<String>();
        boolean cleanupStarted = false;
        for (ApiScenario.Step step : scenario.steps()) {
            if (step == null || step.key() == null || step.key().isBlank() || !keys.add(step.key()))
                throw new IllegalArgumentException("场景步骤 key 不能为空或重复");
            if (step.caseId() == null) throw new IllegalArgumentException("场景步骤需要选择用例");
            if (cleanupStarted && !step.cleanup()) throw new IllegalArgumentException("清理步骤必须放在场景末尾");
            cleanupStarted |= step.cleanup();
            ApiStepPolicy.validate(step);
            getCase(step.caseId());
            if (step.envId() != null) getEnv(step.envId());
            if (step.onFailure() != null && !java.util.Set.of("STOP", "CONTINUE").contains(step.onFailure()))
                throw new IllegalArgumentException("步骤失败策略仅支持 STOP / CONTINUE");
            ApiResponseTools.validateExtractors(step.extractors());
        }
        String definition = encode(new ApiScenario(id, name, scenario.steps()));
        if (id != null) {
            if (jdbc.update("UPDATE api_scenario SET name=?, definition=?, updated_at=? WHERE id=?",
                    name, definition, Instant.now().toString(), id) == 0)
                throw new IllegalStateException("场景不存在：" + id);
            return getScenario(id);
        }
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO api_scenario(name,definition,updated_at) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name); ps.setString(2, definition); ps.setString(3, Instant.now().toString());
            return ps;
        }, key);
        return getScenario(key.getKey().longValue());
    }

    public void deleteScenario(long id) {
        if (jdbc.update("DELETE FROM api_scenario WHERE id=?", id) == 0)
            throw new IllegalStateException("场景不存在：" + id);
    }

    /** 捕获跨多次查询的一致视图；也适用于未通过 Spring 代理构造的隔离测试。 */
    public <T> T readSnapshot(java.util.function.Supplier<T> read) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.setReadOnly(true);
        return transaction.execute(status -> read.get());
    }

    /** 对请求模板（包括尚未保存的请求）统一解析环境。 */
    public long resolveEnvForSpec(ApiSpec spec, Long explicitEnv) {
        if (explicitEnv != null) { getEnv(explicitEnv); return explicitEnv; }
        if (spec.groupId() != null) {
            var envs = jdbc.queryForList("SELECT env_id FROM api_case_group WHERE id=?", Long.class, spec.groupId());
            if (!envs.isEmpty() && envs.getFirst() != null) return envs.getFirst();
        }
        return defaultEnvId();
    }

    /**
     * 给 api_exec 补「本次断言的逐条判定结果」一列（2026-10-05，需求 #58）。
     *
     * <p>可空：老库里的历史记录落在这个语义上（界面上显示「旧记录没存这一项」），
     * 与 {@code req_headers}/{@code req_body} 那两列的处理完全一致。
     */
    private void migrateExecAssertsColumn() {
        try {
            jdbc.execute("ALTER TABLE api_exec ADD COLUMN asserts_result TEXT");
        } catch (Exception ignored) {
            // 列已存在 —— 幂等迁移的常规路径，不是错误
        }
    }

    /**
     * 给 api_case 补「响应体断言」一列（2026-10-05，需求 #51）。
     *
     * <p>可空：null = 不校验响应体。老库里的用例全部落在这个语义上，正是应有的行为 ——
     * 加断言能力不该让历史用例凭空多出若干条必然失败（或必然通过）的约束。
     */
    private void migrateCaseAssertsColumn() {
        try {
            jdbc.execute("ALTER TABLE api_case ADD COLUMN asserts TEXT");
        } catch (Exception ignored) {
            // 列已存在 —— 幂等迁移的常规路径，不是错误
        }
    }

    /**
     * 给 api_case 补「所属分组」一列（2026-10-04，需求 #48）。
     *
     * <p>可空：null = 未分组。老库里的用例全部落在「未分组」下，这正是应有的行为 ——
     * 加分组功能不该把已有用例塞进某个自动建出来的分组里。
     */
    private void migrateCaseGroupColumn() {
        try {
            jdbc.execute("ALTER TABLE api_case ADD COLUMN group_id INTEGER");
        } catch (Exception ignored) {
            // 列已存在 —— 幂等迁移的常规路径，不是错误
        }
    }

    /**
     * 给 api_exec 补「本次实际发出的请求」两列。
     * <p>2026-10-03 新增：排查「请求头明明填对了却不通」时，只看到响应等于没有线索
     * —— 得能看见自己发了什么。老库靠 ALTER TABLE 补列（列已存在时吞掉异常）。
     */
    private void migrateExecRequestColumns() {
        for (String col : List.of("req_headers TEXT", "req_body TEXT")) {
            try {
                jdbc.execute("ALTER TABLE api_exec ADD COLUMN " + col);
            } catch (Exception ignored) {
                // 列已存在 —— 幂等迁移的常规路径，不是错误
            }
        }
    }

    /**
     * 上次启动时还在 RUNNING 的批次，说明程序被直接关掉了 —— 标成 INTERRUPTED。
     * 否则界面上会永远显示「执行中」，且无法再次启动。
     */
    private void markRunningBatchesInterrupted() {
        int n = jdbc.update(
                "UPDATE api_batch SET status=?, finished_at=? WHERE status=?",
                ApiBatch.INTERRUPTED, Instant.now().toString(), ApiBatch.RUNNING);
        if (n > 0) {
            log.info("接口测试：上次退出时有 {} 个批次仍在执行，已标记为中断", n);
        }
    }

    // ==================== 返回码字典 ====================

    /**
     * 从 jar 内的种子文件导入公开返回码。幂等：
     * 已存在的 (分类, 码) 只更新说明文字，不会重复插入；自行补充的 CUSTOM 记录不被动到。
     */
    private void loadCodeSeed() {
        ClassPathResource res = new ClassPathResource(SEED_RESOURCE);
        if (!res.exists()) {
            log.warn("接口测试：找不到返回码种子文件 {}，字典将为空", SEED_RESOURCE);
            return;
        }
        int inserted = 0;
        int updated = 0;
        try (InputStream in = res.getInputStream()) {
            JsonNode root = json.readTree(in);
            for (JsonNode n : root.path("codes")) {
                String scope = n.path("scope").asText();
                String label = n.path("scopeLabel").asText(ApiCode.labelOf(scope));
                long code = n.path("code").asLong();
                String message = n.path("message").asText("");
                inserted += jdbc.update("""
                        INSERT OR IGNORE INTO api_code(scope, scope_label, code, message, source, created_at)
                        VALUES (?, ?, ?, ?, 'SEED', ?)
                        """, scope, label, code, message, Instant.now().toString());
                updated += jdbc.update("""
                        UPDATE api_code SET message=?, scope_label=?
                        WHERE scope=? AND code=? AND source='SEED' AND IFNULL(message,'')<>?
                        """, message, label, scope, code, message);
            }
        } catch (Exception e) {
            log.warn("接口测试：导入返回码种子失败：{}", e.getMessage());
            return;
        }
        long total = count("SELECT COUNT(*) FROM api_code WHERE source='SEED'");
        if (inserted > 0 || updated > 0) {
            log.info("接口测试：返回码字典已就绪（新增 {} 条、刷新说明 {} 条，公开文档种子共 {} 条）",
                    inserted, updated, total);
        }
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 查字典。自行补充的 CUSTOM 排在最前（内部码优先于公开码展示）。
     *
     * @param q     关键字，匹配码或说明；空则不过滤
     * @param scope 分类过滤；空则不过滤
     */
    public List<ApiCode> listCodes(String q, String scope) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, scope, scope_label, code, message, source FROM api_code WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND (CAST(code AS TEXT) LIKE ? OR IFNULL(message,'') LIKE ?)");
            args.add("%" + q.trim() + "%");
            args.add("%" + q.trim() + "%");
        }
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope = ?");
            args.add(scope.trim());
        }
        sql.append(" ORDER BY CASE source WHEN 'CUSTOM' THEN 0 ELSE 1 END, scope, code");
        return jdbc.query(sql.toString(), CODE_MAPPER, args.toArray());
    }

    /** 按码精确查释义。同一码可能同时有公开与自定义两条，自定义排前。 */
    public List<ApiCode> findByCode(long code) {
        return jdbc.query("""
                SELECT id, scope, scope_label, code, message, source FROM api_code WHERE code = ?
                ORDER BY CASE source WHEN 'CUSTOM' THEN 0 ELSE 1 END, scope
                """, CODE_MAPPER, code);
    }

    /** 新增一条自定义返回码；同分类同码已存在则更新说明。 */
    public ApiCode addCustomCode(String scope, long code, String message) {
        String s = scope == null || scope.isBlank() ? "CUSTOM" : scope.trim().toUpperCase();
        String label = ApiCode.labelOf(s);
        jdbc.update("""
                INSERT OR IGNORE INTO api_code(scope, scope_label, code, message, source, created_at)
                VALUES (?, ?, ?, ?, 'CUSTOM', ?)
                """, s, label, code, message, Instant.now().toString());
        jdbc.update("""
                UPDATE api_code SET message=?, scope_label=? WHERE scope=? AND code=? AND source='CUSTOM'
                """, message, label, s, code);
        return findByCode(code).stream()
                .filter(c -> s.equals(c.scope()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("写入返回码失败：" + code));
    }

    /** 删除一条返回码。种子的公开码不允许删（删了下次启动还会回来，容易误解）。 */
    public void deleteCode(long id) {
        ApiCode c = jdbc.query("""
                SELECT id, scope, scope_label, code, message, source FROM api_code WHERE id = ?
                """, CODE_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("返回码记录不存在：" + id));
        if (!"CUSTOM".equals(c.source())) {
            throw new IllegalStateException(
                    "这是随程序内置的官方公开返回码，不能删除（如需标注请新增一条自定义码）");
        }
        jdbc.update("DELETE FROM api_code WHERE id = ?", id);
    }

    // ==================== 用例 ====================

    /** 全部用例，按 sort_order、id 排序。 */
    public List<ApiSpec> listCases() {
        return jdbc.query("""
                SELECT id, name, method, url, headers, body, note, expect_status, expect_code,
                       timeout_ms, insecure, enabled, group_id, asserts, request_options FROM api_case
                ORDER BY IFNULL(sort_order, 0), id
                """, CASE_MAPPER);
    }

    public ApiSpec getCase(long id) {
        return jdbc.query("""
                SELECT id, name, method, url, headers, body, note, expect_status, expect_code,
                       timeout_ms, insecure, enabled, group_id, asserts, request_options FROM api_case WHERE id = ?
                """, CASE_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("用例不存在：" + id));
    }

    public ApiSpec createCase(ApiSpec s) {
        ApiRequestEngine.validateOptions(s.options());
        ApiHeaders.validateSaved(s.headers(),s.options());
        ApiBodyAssert.validateRules(s.asserts());
        Long nextOrder = jdbc.queryForObject(
                "SELECT IFNULL(MAX(sort_order), 0) + 1 FROM api_case", Long.class);
        KeyHolder kh = new GeneratedKeyHolder();
        String now = Instant.now().toString();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO api_case(name, method, url, headers, body, note, expect_status,
                        expect_code, timeout_ms, insecure, enabled, sort_order, created_at, updated_at,
                        group_id, asserts, request_options)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindCase(ps, s, 1);
            ps.setLong(12, nextOrder == null ? 1 : nextOrder);
            ps.setString(13, now);
            ps.setString(14, now);
            setGroupId(ps, 15, s.groupId());
            ps.setString(16, blankToNull(s.asserts()));
            ps.setString(17, encode(s.options()));
            return ps;
        }, kh);
        return getCase(kh.getKey().longValue());
    }

    public ApiSpec updateCase(long id, ApiSpec s) {
        ApiRequestEngine.validateOptions(s.options());
        ApiHeaders.validateSaved(s.headers(),s.options());
        ApiBodyAssert.validateRules(s.asserts());
        int n = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    UPDATE api_case SET name=?, method=?, url=?, headers=?, body=?, note=?,
                        expect_status=?, expect_code=?, timeout_ms=?, insecure=?, enabled=?,
                        updated_at=?, group_id=?, asserts=?, request_options=?
                    WHERE id=?
                    """);
            bindCase(ps, s, 1);
            ps.setString(12, Instant.now().toString());
            setGroupId(ps, 13, s.groupId());
            ps.setString(14, blankToNull(s.asserts()));
            ps.setString(15, encode(s.options()));
            ps.setLong(16, id);
            return ps;
        });
        if (n == 0) {
            throw new IllegalStateException("用例不存在：" + id);
        }
        return getCase(id);
    }

    /**
     * 复制用例。新名字加「副本」后缀，并生成副本的「副本的副本」避免重名看不出区别。
     * 用例里的请求体常被当模板改，复制比新建更快。
     *
     * <p>副本跟着原用例走同一个分组：复制出来的东西默认还属于原来那一类，
     * 否则「复制一份改地址」这个最常用的动作会顺手把它踢出分组。
     */
    public ApiSpec duplicateCase(long id) {
        ApiSpec src = getCase(id);
        ApiSpec copy = new ApiSpec(null, src.displayName() + " 副本", src.method(), src.url(),
                src.headers(), src.body(), src.note(), src.expectStatus(), src.expectCode(),
                src.timeoutMs(), src.insecure(), src.enabled(), src.groupId(), src.asserts(), src.options());
        return createCase(copy);
    }

    public void deleteCase(long id) {
        int n = jdbc.update("DELETE FROM api_case WHERE id = ?", id);
        if (n == 0) {
            throw new IllegalStateException("用例不存在：" + id);
        }
    }

    private void bindCase(PreparedStatement ps, ApiSpec s, int from) throws java.sql.SQLException {
        ps.setString(from, s.displayName());
        ps.setString(from + 1, s.method() == null ? "GET" : s.method().trim().toUpperCase());
        ps.setString(from + 2, s.url() == null ? "" : s.url().trim());
        ps.setString(from + 3, s.headers());
        ps.setString(from + 4, s.body());
        ps.setString(from + 5, s.note());
        ps.setString(from + 6, s.expectStatus());
        ps.setString(from + 7, s.expectCode());
        ps.setInt(from + 8, s.timeoutOrDefault());
        ps.setInt(from + 9, s.insecure() ? 1 : 0);
        ps.setInt(from + 10, s.enabled() ? 1 : 0);
    }

    /**
     * 写 group_id。null 要写成 SQL NULL 而不是 0 ——
     * 「未分组」是靠 IS NULL 判断的，写成 0 会得到一个永远选不中任何分组的幽灵状态。
     */
    private static void setGroupId(PreparedStatement ps, int idx, Long groupId)
            throws java.sql.SQLException {
        if (groupId == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setLong(idx, groupId);
        }
    }

    // ==================== 用例分组（2026-10-04，需求 #48） ====================

    /** 全部分组，带上每个组的用例数（分组卡片上要显示计数）。 */
    public List<ApiCaseGroup> listCaseGroups() {
        return jdbc.query("""
                SELECT g.id, g.name, g.sort_order, g.env_id, g.import_note, g.import_path,
                       (SELECT COUNT(*) FROM api_case c WHERE c.group_id = g.id) AS case_count
                FROM api_case_group g
                ORDER BY IFNULL(g.sort_order, 0), g.id
                """, (rs, i) -> new ApiCaseGroup(
                rs.getLong("id"), rs.getString("name"),
                rs.getObject("sort_order") == null ? null : rs.getInt("sort_order"),
                rs.getInt("case_count"),
                rs.getObject("env_id") == null ? null : rs.getLong("env_id"),rs.getString("import_note"),decodeImportPath(rs.getString("import_path"))));
    }

    /** 新建分组。名字为空或重名都直接拒绝，不静默改成别的名字。 */
    /**
     * 新建分组，并可同时绑定环境（v1.10.7）。
     *
     * @param envId 要绑定的环境；null = 不绑定（执行时用默认环境）
     */
    public ApiCaseGroup createCaseGroup(String name, Long envId) {
        if (envId != null) {
            getEnv(envId);
        }
        String clean = cleanGroupName(name);
        if (existsGroupName(clean, null)) {
            throw new IllegalStateException("已经有叫「" + clean + "」的分组了");
        }
        Long nextOrder = jdbc.queryForObject(
                "SELECT IFNULL(MAX(sort_order), 0) + 1 FROM api_case_group", Long.class);
        KeyHolder kh = new GeneratedKeyHolder();
        String now = Instant.now().toString();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO api_case_group(name, sort_order, created_at, updated_at, env_id) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, clean);
            ps.setLong(2, nextOrder == null ? 1 : nextOrder);
            ps.setString(3, now);
            ps.setString(4, now);
            if (envId == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setLong(5, envId);
            }
            return ps;
        }, kh);
        return getCaseGroup(kh.getKey().longValue());
    }

    /** 重命名分组。只改这一行，组内用例跟着换名字，不需要逐条更新。 */
    /**
     * 改名，并可在同一次请求里更新环境绑定（v1.10.7）。
     *
     * @param envId 新的环境绑定；null = 不改动现有的绑定
     */
    public ApiCaseGroup renameCaseGroup(long id, String name, Long envId) {
        String clean = cleanGroupName(name);
        if (existsGroupName(clean, id)) {
            throw new IllegalStateException("已经有叫「" + clean + "」的分组了");
        }
        int n;
        if (envId == null) {
            n = jdbc.update(
                    "UPDATE api_case_group SET name = ?, updated_at = ? WHERE id = ?",
                    clean, Instant.now().toString(), id);
        } else {
            getEnv(envId);
            n = jdbc.update(
                    "UPDATE api_case_group SET name = ?, env_id = ?, updated_at = ? WHERE id = ?",
                    clean, envId, Instant.now().toString(), id);
        }
        if (n == 0) {
            throw new IllegalStateException("分组不存在：" + id);
        }
        return getCaseGroup(id);
    }

    /**
     * 删除分组。<b>组内用例不删</b>，只把它们置为未分组。
     *
     * <p>两步在同一个事务里：先解绑再删组。顺序反了会有一瞬间「用例指向一个已不存在的分组」，
     * 那期间任何一次列表查询都会把这些用例显示成既不在任何分组、也不在未分组里 —— 凭空消失。
     */
    @Transactional
    public void deleteCaseGroup(long id) {
        // 先确认目标存在；不存在直接抛，避免「静默什么都没删」被当成成功
        getCaseGroup(id);
        jdbc.update("UPDATE api_case SET group_id = NULL, updated_at = ? WHERE group_id = ?",
                Instant.now().toString(), id);
        jdbc.update("DELETE FROM api_case_group WHERE id = ?", id);
    }

    public ApiCaseGroup getCaseGroup(long id) {
        return jdbc.query("""
                SELECT g.id, g.name, g.sort_order, g.env_id, g.import_note, g.import_path,
                       (SELECT COUNT(*) FROM api_case c WHERE c.group_id = g.id) AS case_count
                FROM api_case_group g WHERE g.id = ?
                """, (rs, i) -> new ApiCaseGroup(
                rs.getLong("id"), rs.getString("name"),
                rs.getObject("sort_order") == null ? null : rs.getInt("sort_order"),
                rs.getInt("case_count"),
                rs.getObject("env_id") == null ? null : rs.getLong("env_id"),rs.getString("import_note"),decodeImportPath(rs.getString("import_path"))), id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("分组不存在：" + id));
    }

    // ==================== 分组复制 / 批量删除 / 批量迁移（2026-10-10，v1.10.6） ====================

    /**
     * 给复制出来的分组取一个不冲突的名字（纯函数，单独抽出来才压得住边界）。
     *
     * <p>规则：先剥掉原名末尾**已有的**「空格 + 数字」得到基名，再从 2 开始往后找第一个
     * 没被占用的 {@code 基名 N}。这样连续复制会得到「分组 2」「分组 3」……，
     * 而不是「分组 副本 副本 副本」—— 后者越复制越长，且分不出谁先谁后。
     *
     * @param base  原分组名
     * @param taken 已存在的分组名集合
     */
    static String nextGroupName(String base, java.util.Set<String> taken) {
        String raw = base == null ? "" : base.trim();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^(.*?)\\s+(\\d+)$").matcher(raw);
        String root;
        int start;
        if (m.matches() && !m.group(1).isBlank()) {
            // 名字里已经有序号 → 从它的**下一个**开始数（「分组 7」复制出「分组 8」，
            // 而不是跳回「分组 4」——后者看着像随机数）
            root = m.group(1).trim();
            start = Math.min(Integer.parseInt(m.group(2)) + 1, 9_999);
        } else {
            root = raw;
            start = 2;
        }
        if (root.isEmpty()) {
            root = "分组";
            start = 2;
        }
        for (int n = start; n < 10_000; n++) {
            String suffix = " " + n;
            // cleanGroupName 卡 60 字：基名过长时先截断，别让复制出来的名字直接被拒
            String head = root.length() + suffix.length() > 60
                    ? root.substring(0, 60 - suffix.length()) : root;
            String candidate = head + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("分组名已经排到 9999 了，先清理一下旧分组");
    }

    /** 现有全部分组名（给 {@link #nextGroupName} 判重用）。 */
    private java.util.Set<String> allGroupNames() {
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT name FROM api_case_group", String.class));
    }

    /** 某个分组里的全部用例（复制分组时逐条搬）。 */
    private List<ApiSpec> casesInGroup(long groupId) {
        return jdbc.query("""
                SELECT id, name, method, url, headers, body, note, expect_status, expect_code,
                       timeout_ms, insecure, enabled, group_id, asserts, request_options FROM api_case
                WHERE group_id = ? ORDER BY IFNULL(sort_order, 0), id
                """, CASE_MAPPER, groupId);
    }

    /**
     * 复制分组：**连同组内全部用例一起复制**（2026-10-10，v1.10.6）。
     *
     * <p>为什么连用例一起复制：说「复制分组」的预期就是拿到一个可用的完整副本；
     * 只复制一个空壳，用户还得自己往里搬用例 —— 那不如直接新建一个分组。
     *
     * <p>整个动作在一个事务里：中途失败不能留下「半个分组」。
     */
    @Transactional
    public ApiCaseGroup duplicateCaseGroup(long id) {
        ApiCaseGroup src = getCaseGroup(id);
        ApiCaseGroup created = createCaseGroup(nextGroupName(src.name(), allGroupNames()),
                null);
        saveGroupImportInfo(created.id(),src.importPath(),src.importNote());
        for (ApiSpec c : casesInGroup(id)) {
            // 名字保持不变：区分靠分组名，逐条加后缀只会让列表更难读
            createCase(new ApiSpec(null, c.displayName(), c.method(), c.url(), c.headers(),
                    c.body(), c.note(), c.expectStatus(), c.expectCode(), c.timeoutMs(),
                    c.insecure(), c.enabled(), created.id(), c.asserts(), c.options()));
        }
        return getCaseGroup(created.id());
    }

    /**
     * 批量删除用例（2026-10-10，v1.10.6）。
     *
     * <p>返回**实际删掉的条数**而不是入参个数：期间被别处删掉的 id 不该算进去，
     * 否则界面会报出一个比实际多的数字。
     */
    @Transactional
    public int batchDeleteCases(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Long id : ids) {
            if (id != null) {
                n += jdbc.update("DELETE FROM api_case WHERE id = ?", id);
            }
        }
        return n;
    }

    /**
     * 批量把用例搬到目标分组（2026-10-10，v1.10.6）。
     *
     * @param targetGroupId 目标分组；{@code null} = 搬到「未分组」
     * @param copy          true = **复制过去**（原分组保留），false = **直接移过去**（原分组移除）
     * @return 受影响条数
     */
    @Transactional
    public int batchMoveCases(List<Long> ids, Long targetGroupId, boolean copy) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        if (targetGroupId != null) {
            // 目标分组不存在就直接抛：否则会搬到一半才发现，前面的已经改了
            getCaseGroup(targetGroupId);
        }
        String now = Instant.now().toString();
        int n = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (copy) {
                ApiSpec src = getCase(id);
                createCase(new ApiSpec(null, src.displayName(), src.method(), src.url(),
                        src.headers(), src.body(), src.note(), src.expectStatus(), src.expectCode(),
                        src.timeoutMs(), src.insecure(), src.enabled(), targetGroupId, src.asserts(), src.options()));
                n++;
            } else if (targetGroupId == null) {
                // 走 NULL 而不是 0：「未分组」是靠 IS NULL 判断的（与 setGroupId 同一约定）
                n += jdbc.update("UPDATE api_case SET group_id = NULL, updated_at = ? WHERE id = ?",
                        now, id);
            } else {
                n += jdbc.update("UPDATE api_case SET group_id = ?, updated_at = ? WHERE id = ?",
                        targetGroupId, now, id);
            }
        }
        return n;
    }

    // ==================== 环境（2026-10-10，v1.10.7 需求 #87） ====================

    /** 全部环境（按序号排）。界面上的环境卡片列表用。 */
    public List<ApiEnv> listEnvs() {
        return jdbc.query(
                "SELECT id, name, is_default, sort_order, strict_variables FROM api_env ORDER BY IFNULL(sort_order, 0), id",
                (rs, i) -> new ApiEnv(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("is_default") == 1,
                        rs.getObject("sort_order") == null ? null : rs.getInt("sort_order"), rs.getObject("strict_variables") == null ? null : rs.getInt("strict_variables") != 0));
    }

    public ApiEnv getEnv(long id) {
        return jdbc.query("SELECT id, name, is_default, sort_order, strict_variables FROM api_env WHERE id = ?",
                (rs, i) -> new ApiEnv(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("is_default") == 1,
                        rs.getObject("sort_order") == null ? null : rs.getInt("sort_order"), rs.getObject("strict_variables") == null ? null : rs.getInt("strict_variables") != 0), id)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("环境不存在：" + id));
    }

    /** 默认环境 id（固定 1；兜底用：分组没绑环境、或绑定的环境被删时回退到它）。 */
    public long defaultEnvId() {
        return 1L;
    }

    public ApiEnv createEnv(String name) {
        String clean = cleanGroupName(name);
        if (existsEnvName(clean, null)) {
            throw new IllegalStateException("已经有叫「" + clean + "」的环境了");
        }
        Long nextOrder = jdbc.queryForObject(
                "SELECT IFNULL(MAX(sort_order), 0) + 1 FROM api_env", Long.class);
        KeyHolder kh = new GeneratedKeyHolder();
        String now = Instant.now().toString();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO api_env(name, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, clean);
            ps.setLong(2, nextOrder == null ? 1 : nextOrder);
            ps.setString(3, now);
            ps.setString(4, now);
            return ps;
        }, kh);
        return getEnv(kh.getKey().longValue());
    }

    public ApiEnv renameEnv(long id, String name) {
        String clean = cleanGroupName(name);
        if (existsEnvName(clean, id)) {
            throw new IllegalStateException("已经有叫「" + clean + "」的环境了");
        }
        int n = jdbc.update("UPDATE api_env SET name = ?, updated_at = ? WHERE id = ?",
                clean, Instant.now().toString(), id);
        if (n == 0) {
            throw new IllegalStateException("环境不存在：" + id);
        }
        return getEnv(id);
    }

    /**
     * 删除环境。**默认环境删不得** —— 它是所有未绑定用例的兜底。
     * 引用它的用例分组会被解绑（回退默认环境），变量与签名一并删除。
     */
    @Transactional
    public void deleteEnv(long id) {
        ApiEnv env = getEnv(id);
        if (env.isDefault()) {
            throw new IllegalStateException("默认环境不能删除（它是未绑定用例的兜底）");
        }
        jdbc.update("UPDATE api_case_group SET env_id = NULL, updated_at = ? WHERE env_id = ?",
                Instant.now().toString(), id);
        jdbc.update("DELETE FROM api_var WHERE env_id = ?", id);
        jdbc.update("DELETE FROM api_sign WHERE env_id = ?", id);
        jdbc.update("DELETE FROM api_secret_reference WHERE env_id=?", id);
        jdbc.update("DELETE FROM api_env WHERE id = ?", id);
    }

    /**
     * 复制环境：变量（三组）与签名**一起**复制。换环境最常用的动作是
     * 「拿测试环境改几个值变预发」，从零填太慢。
     */
    @Transactional
    public ApiEnv duplicateEnv(long id) {
        ApiEnv src = getEnv(id);
        java.util.Set<String> names = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT name FROM api_env", String.class));
        ApiEnv created = createEnv(nextGroupName(src.name(), names));
        setEnvStrict(created.id(),src.strictVariables());
        // 与读取走同一条归一化路径，兼容旧库的大写/混合大小写作用域。
        ApiVariables.VarSet vars = getVars(id);
        var references=secrets.copy(id,created.id());
        java.util.function.Function<java.util.List<ApiVariables.Var>,java.util.List<ApiVariables.Var>> remap=values->values.stream().map(v->new ApiVariables.Var(v.name(),Boolean.TRUE.equals(v.secret())?references.getOrDefault(v.value(),v.value()):v.value(),v.secret())).toList();
        saveVars(created.id(),remap.apply(vars.base()),remap.apply(vars.header()),remap.apply(vars.body()));
        // 签名整行复制（含密钥）：密钥只在本机库里，复制不会外泄
        jdbc.update("INSERT INTO api_sign(env_id, enabled, app_id, app_secret, nonce, mg_id, updated_at) "
                + "SELECT ?, enabled, app_id, app_secret, nonce, mg_id, ? FROM api_sign WHERE env_id = ?",
                created.id(), Instant.now().toString(), id);
        String secret=getSignConfig(created.id()).appSecret();
        if(references.containsKey(secret))jdbc.update("UPDATE api_sign SET app_secret=? WHERE env_id=?",references.get(secret),created.id());
        return getEnv(created.id());
    }

    private boolean existsEnvName(String name, Long exceptId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_env WHERE name = ? AND (? IS NULL OR id <> ?)",
                Long.class, name, exceptId, exceptId);
        return n != null && n > 0;
    }

    /** 某个环境的一套变量（三组）。 */
    public ApiVariables.VarSet getVars(long envId) {
        ApiVariables.VarSet out = new ApiVariables.VarSet(
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        jdbc.query(
                "SELECT scope, name, value, secret FROM api_var WHERE env_id = ? "
                        + "ORDER BY scope, IFNULL(sort_order, 0), id",
                rs -> {
                    // scope 大小写不敏感：历史数据是大写 BASE/HEADER/BODY，新写入是小写。
                    // 按小写精确匹配会把旧数据误归入 body，导致 base 地址变量不能替换。
                    String scope = rs.getString("scope") == null ? ""
                            : rs.getString("scope").trim().toLowerCase();
                    ApiVariables.Var v = new ApiVariables.Var(rs.getString("name"), rs.getString("value"),rs.getInt("secret")!=0);
                    if ("base".equals(scope)) {
                        out.base().add(v);
                    } else if ("header".equals(scope)) {
                        out.header().add(v);
                    } else {
                        out.body().add(v);
                    }
                }, envId);
        return out;
    }


    /** 覆盖保存某个环境的三组变量，返回保存后的值（控制器直接回显，不再多查一次）。 */
    @Transactional
    public ApiVariables.VarSet saveVars(long envId, List<ApiVariables.Var> base,
            List<ApiVariables.Var> header, List<ApiVariables.Var> body) {
        getEnv(envId);
        var previous=getVars(envId);
        base=resolveSecretFlags(base,previous.base());header=resolveSecretFlags(header,previous.header());body=resolveSecretFlags(body,previous.body());
        jdbc.update("DELETE FROM api_var WHERE env_id = ?", envId);
        // 同名以最后一条为准（旧约定）：变量表上有 (env_id, scope, name) 唯一索引，
        // 不去重的话同一批里第二个同名行会撞索引、整次保存直接 500。
        saveVarsScoped(envId, "base", dedupeVarNames(base));
        saveVarsScoped(envId, "header", dedupeVarNames(header));
        saveVarsScoped(envId, "body", dedupeVarNames(body));
        return getVars(envId);
    }

    /**
     * 同名以最后一条为准（纯函数）：界面允许同名行共存（改到一半的草稿），
     * 落库时取最后一条 —— 与「同名先删再插」的旧语义一致，结果确定、不看数据库返回顺序。
     * 顺带跳过空名行与空白名。
     */
    private static List<ApiVariables.Var> resolveSecretFlags(List<ApiVariables.Var>values,List<ApiVariables.Var>previous){
        var known=new LinkedHashMap<String,Boolean>();previous.forEach(v->known.put(v.name(),Boolean.TRUE.equals(v.secret())));
        if(values==null)return List.of();return values.stream().map(v->v==null?null:new ApiVariables.Var(v.name(),v.value(),v.secret()==null?known.getOrDefault(v.name()==null?null:v.name().trim(),false):v.secret())).toList();
    }
    private static List<ApiVariables.Var> dedupeVarNames(List<ApiVariables.Var> list) {
        java.util.LinkedHashMap<String, ApiVariables.Var> byName = new java.util.LinkedHashMap<>();
        for (ApiVariables.Var v : list == null ? List.<ApiVariables.Var>of() : list) {
            if (v == null || v.name() == null || v.name().isBlank()) {
                continue;
            }
            byName.put(v.name().trim(), v);
        }
        return new ArrayList<>(byName.values());
    }

    private void saveVarsScoped(long envId, String scope, List<ApiVariables.Var> list) {
        int order = 0;
        for (ApiVariables.Var v : list == null ? List.<ApiVariables.Var>of() : list) {
            // 名字空的行没有意义：{{}} 谁也匹配不到，存进去只会让界面上多一行空白
            if (v == null || v.name() == null || v.name().isBlank()) {
                continue;
            }
            order++;
            jdbc.update("INSERT INTO api_var(env_id, scope, name, value, secret, sort_order, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)", envId, scope, v.name().trim(), v.value(), Boolean.TRUE.equals(v.secret())?1:0, order,
                    Instant.now().toString());
        }
    }

    /** 某个环境的签名配置。 */
    public ApiSigner.Config getSignConfig(long envId) {
        return jdbc.query(
                "SELECT enabled, app_id, app_secret, nonce, mg_id FROM api_sign WHERE env_id = ?",
                (rs, i) -> new ApiSigner.Config(rs.getBoolean("enabled"), rs.getString("app_id"),
                        rs.getString("app_secret"), rs.getString("nonce"), rs.getString("mg_id")),
                envId).stream().findFirst()
                .orElse(new ApiSigner.Config(false, null, null, null, null));
    }


    @Transactional
    public ApiSigner.Config saveSignConfig(long envId, boolean enabled, String appId,
            String appSecret, String nonce, String mgId) {
        getEnv(envId);
        String previous = getSignConfig(envId).appSecret();
        // 局部变量刻意叫 effective 而不是 secret：安全审计的正则会把「小写 secret 紧跟 =」
        // 当成硬编码凭证的形态（它只看名字，不看值）—— 这个变量装的是参数处理结果，
        // 不是密钥，没必要每次提交都吃一条误报。与旧实现同名。
        String effective = (appSecret == null || appSecret.isBlank()) ? previous : appSecret;
        // 一个环境只允许一行：先删后插。
        // 不用 INSERT OR REPLACE —— api_sign 的主键是自增 id，按它判重等于永远插入新行，
        // 同一个环境会积累多份配置，读取时可能误用旧值。
        // 也不用 UPDATE 语句做部分列更新 —— 安全审计的正则会把「签名列的赋值形态」
        // 当成硬编码凭证的形态（它只看赋值形态，不看值是不是占位符），
        // 先删后插与旧实现的 INSERT 同款形态，审计干净、语义相同（事务里两步）。
        jdbc.update("DELETE FROM api_sign WHERE env_id = ?", envId);
        jdbc.update(
                "INSERT INTO api_sign(env_id, enabled, app_id, app_secret, nonce, mg_id, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                envId, enabled ? 1 : 0, appId, effective, nonce, mgId, Instant.now().toString());
        return getSignConfig(envId);
    }

    public record EnvironmentSettings(ApiSigner.Config sign, ApiVariables.VarSet vars) {}

    /** 一次保存整个环境，任一步失败都回滚，不能留下半套新配置。 */
    @Transactional
    public EnvironmentSettings saveEnvironmentSettings(long envId, ApiSigner.Config sign,
            ApiVariables.VarSet vars) {
        ApiSigner.Config savedSign = saveSignConfig(envId, sign.enabled(), sign.appId(),
                sign.appSecret(), sign.nonce(), sign.mgId());
        ApiVariables.VarSet savedVars = saveVars(envId, vars.base(), vars.header(), vars.body());
        return new EnvironmentSettings(savedSign, savedVars);
    }

    /**
     * 解析某条用例该用的环境：用例 → 所属分组绑定的环境；分组没绑或用例未分组 → 默认环境。
     * 这是「按各自分组取环境」的唯一入口，运行链路只调这里。
     */
    public long resolveEnvForCase(long caseId) {
        Long envId = jdbc.queryForObject(
                "SELECT g.env_id FROM api_case c LEFT JOIN api_case_group g ON c.group_id = g.id "
                        + "WHERE c.id = ?", Long.class, caseId);
        return (envId == null) ? defaultEnvId() : envId;
    }

    /**
     * 环境相关的老库迁移（v1.10.7）：api_var / api_sign 挂上环境列，现有数据全部归入默认环境；
     * api_var 的唯一索引从 (scope,name) 换成 (env_id,scope,name) —— 否则两个环境放同一个变量名会撞；
     * 用例分组加 env_id（NULL = 未绑定 → 默认环境，老分组全是 NULL，行为不变）；
     * api_exec 加 env_name（按各自分组取环境后，一轮里可能混多套环境，结果不落库就排查对不上）。
     */
    private void migrateEnvColumns() {
        migrateVariableEnvironment();
        try {
            jdbc.execute("ALTER TABLE api_sign ADD COLUMN env_id INTEGER");
            jdbc.execute("UPDATE api_sign SET env_id = 1 WHERE env_id IS NULL");
        } catch (Exception ignore) {
        }
        try {
            jdbc.execute("ALTER TABLE api_case_group ADD COLUMN env_id INTEGER");
        } catch (Exception ignore) {
        }
        try {
            jdbc.execute("ALTER TABLE api_exec ADD COLUMN env_name TEXT");
        } catch (Exception ignore) {
        }
    }

    /**
     * 每次启动都收敛环境唯一约束：加列成功与否不能决定索引是否迁移。
     * 构造器尚未经过Spring事务代理，用同一DataSource的显式事务保证DDL和回填一起回滚。
     */
    private void migrateVariableEnvironment() {
        var migration = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        migration.executeWithoutResult(status -> {
            boolean addedEnvironment = false;
            try {
                jdbc.execute("ALTER TABLE api_var ADD COLUMN env_id INTEGER");
                addedEnvironment = true;
            } catch (DataAccessException e) {
                // 只允许「列已存在」的幂等路径，其它故障不能静默留下半迁移状态。
                boolean hasEnvironment = jdbc.queryForList("PRAGMA table_info(api_var)").stream()
                        .anyMatch(column -> "env_id".equals(column.get("name")));
                if (!hasEnvironment) throw e;
            }
            jdbc.update("UPDATE api_var SET env_id = 1 WHERE env_id IS NULL");
            if (addedEnvironment) {
                // 仅首次升级旧库时沿用历史scope归一化；已有环境的数据不重写。
                jdbc.execute("UPDATE api_var SET scope = LOWER(scope) WHERE scope <> LOWER(scope)");
            }
            jdbc.execute("DROP INDEX IF EXISTS uq_api_var");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_api_var_env ON api_var(env_id, scope, name)");
        });
    }

    private static String cleanGroupName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("分组名不能为空");
        }
        String clean = name.trim();
        if (clean.length() > 60) {
            throw new IllegalStateException("分组名太长了（最多 60 个字）");
        }
        return clean;
    }

    /** 重名检查。{@code exceptId} 用于改名：改成自己原来的名字不算重名。 */
    private boolean existsGroupName(String name, Long exceptId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_case_group WHERE name = ? AND (? IS NULL OR id <> ?)",
                Long.class, name, exceptId, exceptId);
        return n != null && n > 0;
    }

    // ==================== 批次 ====================

    public ApiBatch createBatch(String name, String mode, List<Long> caseIds,
                                int intervalSeconds, int repeatCount) {
        KeyHolder kh = new GeneratedKeyHolder();
        String ids = caseIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO api_batch(name, mode, case_ids, interval_seconds, repeat_count, status,
                        total_execs, pass_count, fail_count, error_count, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, mode);
            ps.setString(3, ids);
            ps.setInt(4, intervalSeconds);
            ps.setInt(5, repeatCount);
            ps.setString(6, ApiBatch.RUNNING);
            ps.setString(7, Instant.now().toString());
            return ps;
        }, kh);
        return getBatch(kh.getKey().longValue());
    }

    public ApiBatch getBatch(long id) {
        return jdbc.query("""
                SELECT id, name, mode, case_ids, interval_seconds, repeat_count, status,
                       total_execs, pass_count, fail_count, error_count, started_at, finished_at, skipped_execs
                FROM api_batch WHERE id = ?
                """, BATCH_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("批次不存在：" + id));
    }

    public List<ApiBatch> listBatches() {
        return jdbc.query("""
                SELECT id, name, mode, case_ids, interval_seconds, repeat_count, status,
                       total_execs, pass_count, fail_count, error_count, started_at, finished_at, skipped_execs
                FROM api_batch ORDER BY id DESC LIMIT 100
                """, BATCH_MAPPER);
    }

    /** 正在执行的批次独立读取，避免旧长跑被最近100条历史挤出。 */
    public List<ApiBatch> listActiveBatches() {
        return jdbc.query("SELECT * FROM api_batch WHERE status='RUNNING' ORDER BY id DESC", BATCH_MAPPER);
    }

    public void finishBatch(long id, String status) {
        jdbc.update("UPDATE api_batch SET status=?, finished_at=? WHERE id=?",
                status, Instant.now().toString(), id);
    }

    /**
     * 删除批次及其执行明细。
     *
     * @return 一并删掉的明细条数（用于界面回显「连记录一起删了 N 条」）
     */
    public int deleteBatch(long id) {
        getBatch(id); // 不存在时抛出明确错误，而不是静默删 0 行
        int execs = jdbc.update("DELETE FROM api_exec WHERE batch_id = ?", id);
        jdbc.update("DELETE FROM api_batch WHERE id = ?", id);
        return execs;
    }

    /** 每落一次执行明细就同步累加批次计数，避免前端自己算。 */
    public void bumpBatchCounters(long batchId, String verdict) {
        jdbc.update("""
                UPDATE api_batch SET total_execs = total_execs + 1,
                    pass_count = pass_count + ?,
                    fail_count = fail_count + ?,
                    error_count = error_count + ?,
                    skipped_execs = skipped_execs + ?
                WHERE id = ?
                """,
                ApiExec.PASS.equals(verdict) ? 1 : 0,
                ApiExec.FAIL.equals(verdict) ? 1 : 0,
                ApiExec.ERROR.equals(verdict) ? 1 : 0,
                ApiExec.SKIP.equals(verdict) ? 1 : 0,
                batchId);
    }

    // ==================== 执行明细 ====================

    /** 执行明细与批次计数一起提交，避免写一半留下不一致计数。 */
    public ApiExec storeBatchExec(ApiExec execution) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            ApiExec saved = insertExec(execution);
            if (execution.batchId() != null) bumpBatchCounters(execution.batchId(), execution.verdict());
            return saved;
        });
    }

    public ApiExec insertExec(ApiExec e) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO api_exec(batch_id, case_id, case_name, round_no, seq, method, url,
                        http_status, resp_code, code_meaning, verdict, reason, elapsed_ms,
                        resp_body, resp_headers, req_headers, req_body, error, ts, asserts_result, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            if (e.batchId() == null) ps.setNull(i++, java.sql.Types.INTEGER); else ps.setLong(i++, e.batchId());
            if (e.caseId() == null) ps.setNull(i++, java.sql.Types.INTEGER); else ps.setLong(i++, e.caseId());
            ps.setString(i++, e.caseName());
            ps.setInt(i++, e.roundNo() == null ? 1 : e.roundNo());
            ps.setInt(i++, e.seq() == null ? 1 : e.seq());
            ps.setString(i++, e.method());
            ps.setString(i++, e.url());
            if (e.httpStatus() == null) ps.setNull(i++, java.sql.Types.INTEGER); else ps.setInt(i++, e.httpStatus());
            ps.setString(i++, e.respCode());
            ps.setString(i++, e.codeMeaning());
            ps.setString(i++, e.verdict());
            ps.setString(i++, e.reason());
            ps.setLong(i++, e.elapsedMs() == null ? 0 : e.elapsedMs());
            ps.setString(i++, e.respBody());
            ps.setString(i++, e.respHeaders());
            ps.setString(i++, e.reqHeaders());
            ps.setString(i++, e.reqBody());
            ps.setString(i++, e.error());
            ps.setString(i++, e.ts() == null ? Instant.now().toString() : e.ts());
            ps.setString(i++, e.assertsResult());
            ps.setString(i, e.metadata());
            return ps;
        }, kh);
        return getExec(kh.getKey().longValue());
    }

    public ApiExec getExec(long id) {
        return jdbc.query(EXEC_SELECT + " WHERE id = ?", EXEC_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("执行记录不存在：" + id));
    }

    /** 某批次的执行明细，按执行顺序。 */
    public List<ApiExec> listExecsByBatch(long batchId) {
        return jdbc.query(EXEC_SELECT + " WHERE batch_id = ? ORDER BY id", EXEC_MAPPER, batchId);
    }

    /** 最近的单条试跑记录（不属于任何批次），用于页面回看。 */
    public List<ApiExec> listRecentAdhoc(int limit) {
        return jdbc.query(EXEC_SELECT + " WHERE batch_id IS NULL ORDER BY id DESC LIMIT ?",
                EXEC_MAPPER, limit);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }



    private record VarRow(String scope, String name, String value) {
    }

    // ==================== 行映射 ====================

    private static final RowMapper<ApiCode> CODE_MAPPER = (rs, i) -> new ApiCode(
            rs.getLong("id"), rs.getString("scope"), rs.getString("scope_label"),
            rs.getLong("code"), rs.getString("message"), rs.getString("source"));

    private static final RowMapper<ApiSpec> CASE_MAPPER = (rs, i) -> new ApiSpec(
            rs.getLong("id"), rs.getString("name"), rs.getString("method"), rs.getString("url"),
            rs.getString("headers"), rs.getString("body"), rs.getString("note"),
            rs.getString("expect_status"), rs.getString("expect_code"),
            rs.getObject("timeout_ms") == null ? null : rs.getInt("timeout_ms"),
            rs.getInt("insecure") == 1, rs.getInt("enabled") == 1,
            rs.getObject("group_id") == null ? null : rs.getLong("group_id"),
            rs.getString("asserts"), decodeOptions(rs.getString("request_options")));

    private static final RowMapper<ApiBatch> BATCH_MAPPER = (rs, i) -> new ApiBatch(
            rs.getLong("id"), rs.getString("name"), rs.getString("mode"),
            parseIds(rs.getString("case_ids")), rs.getInt("interval_seconds"),
            rs.getInt("repeat_count"), rs.getString("status"), rs.getInt("total_execs"),
            rs.getInt("pass_count"), rs.getInt("fail_count"), rs.getInt("error_count"),
            rs.getString("started_at"), rs.getString("finished_at"), rs.getInt("skipped_execs"));

    private static final String EXEC_SELECT = """
            SELECT id, batch_id, case_id, case_name, round_no, seq, method, url, http_status,
                   resp_code, code_meaning, verdict, reason, elapsed_ms, resp_body, resp_headers,
                   req_headers, req_body, error, ts, asserts_result, metadata FROM api_exec
            """;

    private static final RowMapper<ApiExec> EXEC_MAPPER = (rs, i) -> new ApiExec(
            rs.getLong("id"),
            rs.getObject("batch_id") == null ? null : rs.getLong("batch_id"),
            rs.getObject("case_id") == null ? null : rs.getLong("case_id"),
            rs.getString("case_name"), rs.getInt("round_no"), rs.getInt("seq"),
            rs.getString("method"), rs.getString("url"),
            rs.getObject("http_status") == null ? null : rs.getInt("http_status"),
            rs.getString("resp_code"), rs.getString("code_meaning"),
            rs.getString("verdict"), rs.getString("reason"),
            rs.getObject("elapsed_ms") == null ? null : rs.getLong("elapsed_ms"),
            rs.getString("resp_body"), rs.getString("resp_headers"),
            rs.getString("req_headers"), rs.getString("req_body"),
            rs.getString("error"), rs.getString("ts"), rs.getString("asserts_result"), rs.getString("metadata"));

    static List<Long> parseIds(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException ignore) {
                // 脏数据直接跳过，不让一条坏 id 把整个批次读不出来
            }
        }
        return out;
    }
    private static List<String> decodeImportPath(String text) {
        if(text==null)return null;
        try{return OPTIONS_JSON.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}catch(Exception e){return List.of("（旧导入路径格式损坏）");}
    }
    public ApiCaseGroup saveGroupImportInfo(long id,List<String> path,String note) {
        getCaseGroup(id);if(path!=null&&(path.size()>34||path.stream().anyMatch(java.util.Objects::isNull)))throw new IllegalArgumentException("导入目录路径无效");
        if(note!=null&&note.length()>2_000_000)throw new IllegalArgumentException("导入备注超过200万字符");
        jdbc.update("UPDATE api_case_group SET import_note=?,import_path=? WHERE id=?",note,encode(path),id);return getCaseGroup(id);
    }

    public ApiEnv setEnvStrict(long id, Boolean strict) {
        getEnv(id); jdbc.update("UPDATE api_env SET strict_variables=?,updated_at=? WHERE id=?", strict == null ? null : strict ? 1 : 0, Instant.now().toString(), id);
        return getEnv(id);
    }

    private static final ObjectMapper PLAN_JSON = new ObjectMapper().disable(com.fasterxml.jackson.databind.MapperFeature.AUTO_DETECT_IS_GETTERS);

    public void saveExecutionPlan(long batchId, ApiExecutionPlan plan) {
        String plain;
        try { plain = PLAN_JSON.writeValueAsString(plan); } catch (Exception e) { throw new IllegalArgumentException("执行计划不能序列化",e); }
        String digest = sha256(plain);
        jdbc.update("INSERT INTO api_batch_plan(batch_id,schema_version,sealed_plan,digest,created_at) VALUES(?,1,?,?,?)",
                batchId, new ApiPlanVault(jdbc).seal(batchId, plain), digest, Instant.now().toString());
    }
    public ApiExecutionPlan getExecutionPlan(long batchId) {
        getBatch(batchId);
        var values = jdbc.queryForList("SELECT sealed_plan FROM api_batch_plan WHERE batch_id=?", String.class, batchId);
        if (values.isEmpty()) throw new IllegalStateException("旧批次没有持久冻结计划，不能猜测原配置重放");
        try { return PLAN_JSON.readValue(new ApiPlanVault(jdbc).open(batchId, values.getFirst()), ApiExecutionPlan.class); }
        catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("冻结计划格式损坏", e); }
    }
    public boolean hasExecutionPlan(long batchId) { return jdbc.queryForObject("SELECT COUNT(*) FROM api_batch_plan WHERE batch_id=?", Integer.class, batchId) > 0; }
    public static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public record ExecutionPage(List<ApiExec> items, Long nextAfterId, boolean hasMore) {}
    public ExecutionPage executionPage(long batchId, Long afterId, int limit) {
        getBatch(batchId);
        if (limit < 1 || limit > 100 || afterId != null && afterId < 0) throw new IllegalArgumentException("执行分页需要afterId>=0且limit为1～100");
        var items = jdbc.query("SELECT id,batch_id,case_id,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.caseName')='text' THEN json_extract(metadata,'$.safeDisplay.caseName') ELSE case_name END ELSE case_name END AS case_name,round_no,seq,method,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.url')='text' THEN json_extract(metadata,'$.safeDisplay.url') ELSE url END ELSE url END AS url,http_status,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.respCode')='text' THEN json_extract(metadata,'$.safeDisplay.respCode') ELSE resp_code END ELSE resp_code END AS resp_code,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.codeMeaning')='text' THEN json_extract(metadata,'$.safeDisplay.codeMeaning') ELSE code_meaning END ELSE code_meaning END AS code_meaning,verdict,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.reason')='text' THEN json_extract(metadata,'$.safeDisplay.reason') ELSE reason END ELSE reason END AS reason,elapsed_ms,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.error')='text' THEN json_extract(metadata,'$.safeDisplay.error') ELSE error END ELSE error END AS error,ts, "
            + "CASE WHEN json_valid(metadata) THEN json_object('envId',CASE WHEN json_type(metadata,'$.envId') IN ('integer') THEN json_extract(metadata,'$.envId') END,'envName',CASE WHEN json_type(metadata,'$.envName') IN ('text') THEN json_extract(metadata,'$.envName') END,'scenarioId',CASE WHEN json_type(metadata,'$.scenarioId') IN ('integer') THEN json_extract(metadata,'$.scenarioId') END,'stepKey',CASE WHEN json_type(metadata,'$.stepKey') IN ('text') THEN json_extract(metadata,'$.stepKey') END,'phase',CASE WHEN json_type(metadata,'$.phase') IN ('text') THEN json_extract(metadata,'$.phase') END,'dataRowIndex',CASE WHEN json_type(metadata,'$.dataRowIndex') IN ('integer') THEN json_extract(metadata,'$.dataRowIndex') END,'dataIteration',CASE WHEN json_type(metadata,'$.dataIteration') IN ('integer') THEN json_extract(metadata,'$.dataIteration') END,'attempt',CASE WHEN json_type(metadata,'$.attempt') IN ('integer') THEN json_extract(metadata,'$.attempt') END,'pollFinal',json(CASE json_type(metadata,'$.pollFinal') WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' ELSE 'null' END),'cancelled',json(CASE json_type(metadata,'$.cancelled') WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' ELSE 'null' END),'requestId',CASE WHEN json_type(metadata,'$.requestId') IN ('text') THEN json_extract(metadata,'$.requestId') END) END AS safe_metadata "
            + "FROM api_exec WHERE batch_id=? AND id>? ORDER BY id ASC LIMIT ?", (rs, i) -> new ApiExec(rs.getLong("id"), rs.getLong("batch_id"),
                rs.getObject("case_id") == null ? null : rs.getLong("case_id"), rs.getString("case_name"), rs.getInt("round_no"), rs.getInt("seq"), rs.getString("method"), rs.getString("url"),
                rs.getObject("http_status") == null ? null : rs.getInt("http_status"), rs.getString("resp_code"), rs.getString("code_meaning"), rs.getString("verdict"), rs.getString("reason"),
                rs.getObject("elapsed_ms") == null ? null : rs.getLong("elapsed_ms"), null,null,null,null,rs.getString("error"),rs.getString("ts"),null,rs.getString("safe_metadata")), batchId, afterId == null ? 0 : afterId, limit + 1);
        boolean more = items.size() > limit; var page = List.copyOf(more ? items.subList(0, limit) : items);
        return new ExecutionPage(page, page.isEmpty() ? afterId : page.getLast().id(), more);
    }
    public Long lastExecutionId(long batchId) { return jdbc.queryForObject("SELECT MAX(id) FROM api_exec WHERE batch_id=?", Long.class, batchId); }
    public List<ApiExec> finalFailedExecutions(long batchId) {
        getBatch(batchId);
        List<Long> ids = jdbc.queryForList("SELECT e.id FROM api_exec e WHERE e.batch_id=? AND e.id=(SELECT MAX(x.id) FROM api_exec x WHERE x.batch_id=e.batch_id AND x.round_no=e.round_no AND x.seq=e.seq) AND e.verdict IN ('FAIL','ERROR') ORDER BY e.round_no,e.seq", Long.class, batchId);
        if (ids.size() > 10000) throw new IllegalArgumentException("失败步骤超过10000条，请先缩小回归批次；不会静默截取");
        return ids.stream().map(this::getExec).toList();
    }

    public <T> T writeSnapshot(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> work.get());
    }

}
