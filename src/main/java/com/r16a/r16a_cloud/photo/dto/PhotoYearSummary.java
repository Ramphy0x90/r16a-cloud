package com.r16a.r16a_cloud.photo.dto;

import java.io.Serializable;

public record PhotoYearSummary(int year, long count) implements Serializable {}
