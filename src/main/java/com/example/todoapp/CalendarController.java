package com.example.todoapp;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class CalendarController {

    @GetMapping("/calendar")
    public String calendar(@RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month, Model model) {
        YearMonth now = YearMonth.now();
        YearMonth targetMonth;
        YearMonth previousMonth;
        YearMonth nextMonth;
        try {
            targetMonth = YearMonth.of(year == null ? now.getYear() : year,
                    month == null ? now.getMonthValue() : month);
            previousMonth = targetMonth.minusMonths(1);
            nextMonth = targetMonth.plusMonths(1);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "年月が正しくありません", e);
        }

        LocalDate firstDay = targetMonth.atDay(1);
        LocalDate lastDay = targetMonth.atEndOfMonth();
        // 日曜を0、月曜を1、…、土曜を6として、先頭の空のマス数を求める。
        int leadingBlanks = firstDay.getDayOfWeek().getValue() % 7;
        int weekCount = (leadingBlanks + targetMonth.lengthOfMonth() + 6) / 7;
        List<List<LocalDate>> weeks = new ArrayList<>();
        for (int week = 0; week < weekCount; week++) {
            List<LocalDate> days = new ArrayList<>();
            for (int weekday = 0; weekday < 7; weekday++) {
                int dayOfMonth = week * 7 + weekday - leadingBlanks + 1;
                // 月の外のマスは、日付がないことを表すnullにする。
                days.add(dayOfMonth >= 1 && dayOfMonth <= targetMonth.lengthOfMonth()
                        ? targetMonth.atDay(dayOfMonth) : null);
            }
            weeks.add(days);
        }

        model.addAttribute("year", targetMonth.getYear());
        model.addAttribute("month", targetMonth.getMonthValue());
        model.addAttribute("firstDay", firstDay);
        model.addAttribute("lastDay", lastDay);
        model.addAttribute("weeks", weeks);
        model.addAttribute("previousMonth", previousMonth);
        model.addAttribute("nextMonth", nextMonth);
        return "calendar";
    }
}
