package com.devmate.bootstrap.health.controller;

import com.devmate.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Result<String> health() {
        return Result.success("DevMate server running");
    }
}
