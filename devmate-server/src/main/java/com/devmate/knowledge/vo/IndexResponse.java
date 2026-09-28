package com.devmate.knowledge.vo;

public record IndexResponse(IndexSummary latest,IndexSummary active,boolean indexed) {}
