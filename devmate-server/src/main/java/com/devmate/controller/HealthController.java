package com.devmate.controller;

import com.devmate.common.api.Result;
import com.devmate.vo.HealthStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Result<HealthStatusResponse> health() {
        return Result.success(new HealthStatusResponse("UP"));
    }
}
