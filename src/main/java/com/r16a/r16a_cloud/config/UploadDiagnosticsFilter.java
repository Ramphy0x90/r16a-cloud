package com.r16a.r16a_cloud.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TEMP DEBUG
 */
@Slf4j
@Component
public class UploadDiagnosticsFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().endsWith("/fs/upload");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        log.warn("[upload-diag] UA='{}' Content-Type='{}' Content-Length={}",
                request.getHeader("User-Agent"), request.getContentType(), request.getContentLengthLong());
        try {
            for (Part part : request.getParts()) {
                log.warn("[upload-diag] part name='{}' submittedFileName='{}' size={} contentType='{}'",
                        part.getName(), part.getSubmittedFileName(), part.getSize(), part.getContentType());
            }
        } catch (Exception ex) {
            log.warn("[upload-diag] failed to read parts: {}", ex.toString());
        }
        filterChain.doFilter(request, response);
    }
}
