package com.example.todoapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.springframework.context.support.StaticApplicationContext;

class CalendarControllerTests {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SpringResourceTemplateResolver templates = new SpringResourceTemplateResolver();
        templates.setApplicationContext(new StaticApplicationContext());
        templates.setPrefix("classpath:/templates/");
        templates.setSuffix(".html");
        templates.setTemplateMode("HTML");
        templates.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        ThymeleafViewResolver views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");
        mvc = MockMvcBuilders.standaloneSetup(new CalendarController()).setViewResolvers(views).build();
    }

    @ParameterizedTest
    @CsvSource({"2026, 4, 3, 35", "2026, 2, 0, 28", "2026, 8, 6, 42", "2024, 2, 4, 35"})
    void rendersSundayFirstGridWithAttributesOnlyOnRealDates(int year, int month,
            int leadingBlanks, int cellCount) throws Exception {
        YearMonth target = YearMonth.of(year, month);
        String html = render(year, month);
        assertThat(html).contains("data-from=\"" + target.atDay(1) + "\"",
                "data-to=\"" + target.atEndOfMonth() + "\"", ">" + year + "年" + month + "月</h1>");
        assertThat(html).containsSubsequence(">日</th>", ">月</th>", ">火</th>", ">水</th>",
                ">木</th>", ">金</th>", ">土</th>");

        Matcher cells = Pattern.compile("<td([^>]*)>(.*?)</td>", Pattern.DOTALL).matcher(html);
        int cellIndex = 0;
        int dateCount = 0;
        while (cells.find()) {
            int day = cellIndex - leadingBlanks + 1;
            if (day < 1 || day > target.lengthOfMonth()) {
                assertThat(cells.group(1)).doesNotContain("data-date");
                assertThat(cells.group(2)).isBlank();
            } else {
                assertThat(cells.group(1)).contains("data-date=\"" + target.atDay(day) + "\"");
                assertThat(cells.group(2)).contains("<span>" + day + "</span>");
                dateCount++;
            }
            cellIndex++;
        }
        assertThat(cellIndex).isEqualTo(cellCount);
        assertThat(dateCount).isEqualTo(target.lengthOfMonth());
        assertThat(html).doesNotContain("<script", "/api/todos", "/api/holidays");
    }

    @ParameterizedTest
    @CsvSource({"2026, 12, 2026, 11, 2027, 1", "2026, 1, 2025, 12, 2026, 2"})
    void linksCrossYearBoundaries(int year, int month, int previousYear, int previousMonth,
            int nextYear, int nextMonth) throws Exception {
        assertThat(render(year, month)).contains(
                "href=\"/calendar?year=" + previousYear + "&amp;month=" + previousMonth + "\">前の月</a>",
                "href=\"/calendar?year=" + nextYear + "&amp;month=" + nextMonth + "\">次の月</a>");
    }

    @Test
    void omittedParametersUseCurrentYearAndMonthIndependently() throws Exception {
        LocalDate before = LocalDate.now();
        mvc.perform(get("/calendar")).andExpect(status().isOk()).andExpect(view().name("calendar"));
        CalendarController controller = new CalendarController();
        ExtendedModelMap allOmitted = new ExtendedModelMap();
        ExtendedModelMap yearOmitted = new ExtendedModelMap();
        ExtendedModelMap monthOmitted = new ExtendedModelMap();
        controller.calendar(null, null, allOmitted);
        controller.calendar(null, 4, yearOmitted);
        controller.calendar(2024, null, monthOmitted);
        LocalDate after = LocalDate.now();
        assertThat(YearMonth.of((Integer) allOmitted.get("year"), (Integer) allOmitted.get("month")))
                .isIn(YearMonth.from(before), YearMonth.from(after));
        assertThat(yearOmitted.get("year")).isIn(before.getYear(), after.getYear());
        assertThat(yearOmitted.get("month")).isEqualTo(4);
        assertThat(monthOmitted.get("year")).isEqualTo(2024);
        assertThat(monthOmitted.get("month")).isIn(before.getMonthValue(), after.getMonthValue());
    }

    @ParameterizedTest
    @CsvSource({"2026, 0", "2026, 13", "abc, 4"})
    void rejectsInvalidParameters(String year, String month) throws Exception {
        mvc.perform(get("/calendar").param("year", year).param("month", month))
                .andExpect(status().isBadRequest());
    }

    private String render(int year, int month) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/calendar")
                        .param("year", Integer.toString(year)).param("month", Integer.toString(month)))
                .andExpect(status().isOk())
                .andExpect(view().name("calendar"))
                .andReturn().getResponse();
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
