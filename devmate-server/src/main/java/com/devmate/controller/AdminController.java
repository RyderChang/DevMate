package com.devmate.controller;

import com.devmate.common.api.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {
    @GetMapping("/ping")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> ping() {
        return Result.success("pong");
    }
}
