package com.cmo2.strangeloop.controller;

import com.cmo2.strangeloop.dto.LoopDtos.IterationDetailResponse;
import com.cmo2.strangeloop.dto.LoopDtos.IterationResponse;
import com.cmo2.strangeloop.dto.LoopDtos.ScheduleRequest;
import com.cmo2.strangeloop.dto.LoopDtos.ScheduleResponse;
import com.cmo2.strangeloop.memory.CycleRecordService;
import com.cmo2.strangeloop.memory.CycleRecordService.CycleRecord;
import com.cmo2.strangeloop.schedule.LoopScheduleService;
import com.cmo2.strangeloop.service.LoopService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Strange Loop")
public class LoopController {

    private final LoopService loopService;
    private final LoopScheduleService schedules;
    private final CycleRecordService cycleRecords;

    public LoopController(LoopService loopService, LoopScheduleService schedules, CycleRecordService cycleRecords) {
        this.loopService = loopService;
        this.schedules = schedules;
        this.cycleRecords = cycleRecords;
    }

    @GetMapping("/companies/{companyId}/loop/memory")
    @Operation(summary = "The customer's cycle records (decisions, tasks, content, your notes), newest first")
    public List<CycleRecord> memory(@PathVariable Long companyId, @RequestParam(defaultValue = "5") int limit) {
        return cycleRecords.recent(companyId, limit);
    }

    @GetMapping("/companies/{companyId}/loop/schedule")
    @Operation(summary = "The customer's Strange Loop cadence and next run")
    public ScheduleResponse schedule(@PathVariable Long companyId) {
        return schedules.get(companyId);
    }

    @PutMapping("/companies/{companyId}/loop/schedule")
    @Operation(summary = "Set the customer's cadence: OFF, DAILY, WEEKLY or MONTHLY at a time in their timezone")
    public ScheduleResponse setSchedule(@PathVariable Long companyId, @Valid @RequestBody ScheduleRequest request) {
        return schedules.set(companyId, request);
    }

    @GetMapping("/loop/schedules")
    @Operation(summary = "Every customer's cadence and next run")
    public List<ScheduleResponse> schedules() {
        return schedules.list();
    }

    @PostMapping("/companies/{companyId}/loop/cycles")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Run a Strange Loop cycle for a company (async; watch progress over WebSocket)")
    public IterationResponse runCycle(@PathVariable Long companyId) {
        return loopService.runCycle(companyId);
    }

    @GetMapping("/companies/{companyId}/loop/cycles")
    @Operation(summary = "List a company's loop iterations (newest first)")
    public List<IterationResponse> listCycles(@PathVariable Long companyId) {
        return loopService.listIterations(companyId);
    }

    @GetMapping("/loop/cycles/{iterationId}")
    @Operation(summary = "Get a loop iteration with its 5 stage runs + insights/gaps")
    public IterationDetailResponse getCycle(@PathVariable Long iterationId) {
        return loopService.getIteration(iterationId);
    }
}
