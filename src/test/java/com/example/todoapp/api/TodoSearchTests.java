package com.example.todoapp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.Arrays;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.todoapp.Todo;
import com.example.todoapp.TodoMapper;
import com.example.todoapp.TodoService;

class TodoSearchTests {

    private SqlSession session;
    private TodoService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        // Use a separate in-memory database; application data is never accessed.
        var dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:todoSearch;MODE=MySQL", "sa", "");
        var configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        String mapper = "mapper/TodoMapper.xml";
        try (var input = Resources.getResourceAsStream(mapper)) {
            new XMLMapperBuilder(input, configuration, mapper, configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        Connection connection = session.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE todos (
                        id BIGINT PRIMARY KEY, title VARCHAR(255), detail VARCHAR(255),
                        category VARCHAR(255), priority INT, due_date DATE, completed BOOLEAN,
                        created_at TIMESTAMP, updated_at TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO todos (id, title, category, due_date) VALUES
                        (1, 'task before', 'work', '2026-03-31'),
                        (2, 'task start', 'work', '2026-04-01'),
                        (3, 'task end', 'home', '2026-04-30'),
                        (4, 'task after', 'work', '2026-05-01'),
                        (5, 'note without date', 'work', NULL)
                    """);
        }
        service = new TodoService(session.getMapper(TodoMapper.class));
        mvc = MockMvcBuilders.standaloneSetup(new TodoApiController(service))
                .setConversionService(new DefaultFormattingConversionService()).build();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            ", , 5;1;2;3;4",
            "2026-04-01, , 2;3;4",
            ", 2026-04-30, 1;2;3",
            "2026-04-01, 2026-04-30, 2;3",
            "2026-04-01, 2026-04-01, 2",
            "2026-04-30, , 3;4",
            ", 2026-04-01, 1;2",
            "2026-05-01, 2026-04-01, "
    })
    void filtersDatesInclusivelyAndExcludesUndatedTodosWhenBounded(String from, String to,
            String expected) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/todos");
        if (from != null) {
            request.param("from", from);
        }
        if (to != null) {
            request.param("to", to);
        }
        Integer[] ids = expected == null ? new Integer[0]
                : Arrays.stream(expected.split(";")).map(Integer::valueOf).toArray(Integer[]::new);
        var result = mvc.perform(request).andExpect(status().isOk());
        if (ids.length == 0) {
            result.andExpect(jsonPath("$[*].id").isEmpty());
        } else {
            result.andExpect(jsonPath("$[*].id").value(contains(ids)));
        }
    }

    @Test
    void preservesKeywordCategoryAndDescendingOrderWithinRange() throws Exception {
        mvc.perform(get("/api/todos").param("keyword", "task").param("category", "work")
                        .param("order", "desc").param("from", "2026-04-01").param("to", "2026-05-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(4, 2)));
    }

    @Test
    void preservesExistingSearchWithoutDates() throws Exception {
        mvc.perform(get("/api/todos").param("keyword", "task").param("category", "work")
                        .param("order", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(4, 2, 1)));
        mvc.perform(get("/api/todos").param("order", "unexpected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(5, 1, 2, 3, 4)));
        assertThat(service.search("", "", "asc")).extracting(Todo::getId)
                .containsExactly(5L, 1L, 2L, 3L, 4L);
    }

    @ParameterizedTest
    @CsvSource({"from, not-a-date", "to, 2026-13-01"})
    void rejectsInvalidDates(String parameter, String value) throws Exception {
        mvc.perform(get("/api/todos").param(parameter, value))
                .andExpect(status().isBadRequest());
    }
}
