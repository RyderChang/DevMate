package com.devmate.knowledge.application;

import com.devmate.knowledge.vo.ProcessingResponse;

public record ProcessingStart(ProcessingResponse response, boolean accepted) {}
