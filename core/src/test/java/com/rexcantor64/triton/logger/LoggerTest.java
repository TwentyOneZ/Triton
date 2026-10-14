package com.rexcantor64.triton.logger;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggerTest {
    @Test
    void preservesDoubleDigitAuditArgumentsInBothBackends() {
        List<String> messages = new ArrayList<>();
        Logger javaLogger = Logger.getAnonymousLogger();
        javaLogger.setUseParentHandlers(false);
        javaLogger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        org.slf4j.Logger slf4j = (org.slf4j.Logger) Proxy.newProxyInstance(
                org.slf4j.Logger.class.getClassLoader(), new Class<?>[]{org.slf4j.Logger.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("info")) messages.add((String) args[0]);
                    return null;
                });
        for (TritonLogger logger : List.of(new JavaLogger(javaLogger), new SLF4JLogger(slf4j))) {
            logger.setLogLevel(2);
            logger.logTrace("phase=%1 enchantments=%9 tooltipDisplay=%10", "before", 2, 3, 4, 5, 6, 7, 8,
                    "minecraft:protection=4", "hideTooltip=false hidden=[enchantments]");
        }
        assertEquals(List.of(
                "[TRACE] phase=before enchantments=minecraft:protection=4 tooltipDisplay=hideTooltip=false hidden=[enchantments]",
                "[TRACE] phase=before enchantments=minecraft:protection=4 tooltipDisplay=hideTooltip=false hidden=[enchantments]"), messages);
    }
}
