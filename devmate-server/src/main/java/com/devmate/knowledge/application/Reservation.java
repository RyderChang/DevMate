package com.devmate.knowledge.application;

import com.devmate.knowledge.infrastructure.DocumentRow;

public record Reservation(DocumentRow document, boolean created) {}
