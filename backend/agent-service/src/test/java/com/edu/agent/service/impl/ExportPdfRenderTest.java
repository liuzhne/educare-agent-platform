package com.edu.agent.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.edu.agent.entity.AgentTask;
import com.edu.agent.enums.RiskLevel;
import com.edu.agent.enums.TaskStatus;
import com.edu.agent.mapper.AgentExportTaskMapper;
import com.edu.agent.mapper.AgentTaskMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Runs the real template through OpenHTMLToPDF; HTML-only contract tests never reach the XML parser. */
class ExportPdfRenderTest {

    @TempDir
    Path storage;

    private ExportServiceImpl service() throws Exception {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var service = new ExportServiceImpl(mock(AgentExportTaskMapper.class), mock(AgentTaskMapper.class), engine,
                new DefaultResourceLoader(), Runnable::run);
        ReflectionTestUtils.setField(service, "storagePath", storage.toString());
        ReflectionTestUtils.setField(service, "fontPath", "classpath:/fonts/NotoSansSC-Regular.ttf");
        service.init();
        return service;
    }

    @Test
    void modelTextWithXmlInvalidControlCharactersStillRendersReadablePdf() throws Exception {
        JSONObject full = JSON.parseObject(new ClassPathResource("report/react-final.json")
                .getContentAsString(StandardCharsets.UTF_8));
        JSONObject plan = full.getJSONObject("intervention_plan");
        plan.put("summary", "合成摘要\u000B第二段\u0001结束");
        plan.put("talk_outline", "开场\u0000了解￾共情");
        var task = new AgentTask();
        task.setId(7L); task.setStudentId(1L); task.setStatus(TaskStatus.COMPLETED); task.setRiskLevel(RiskLevel.MEDIUM);
        task.setRiskAnalysisResult(full.getJSONObject("risk_analysis").toJSONString());
        task.setInterventionPlan(plan.toJSONString());
        task.setRetrievedKnowledge("{\"executed\":false,\"searches\":[],\"chunks\":[]}");

        ExportServiceImpl service = service();
        File pdf = service.renderPdf(service.renderHtml(task), 3L, 7L);

        String text;
        try (PDDocument document = PDDocument.load(pdf)) {
            text = new PDFTextStripper().getText(document);
        }
        assertThat(text).contains("学业风险干预报告", "合成摘要第二段结束", "开场了解共情");
    }

    @Test
    void stripsOnlyCodePointsOutsideXml10() {
        assertThat(ExportServiceImpl.stripInvalidXmlChars("a\u0000b\u0001c\u000Bd\u000Ce￾f\uD800g"))
                .isEqualTo("abcdefg");
        String allowed = "制表\t换行\n回车\r中文😀𠀀✓";
        assertThat(ExportServiceImpl.stripInvalidXmlChars(allowed)).isEqualTo(allowed);
        assertThat(ExportServiceImpl.stripInvalidXmlChars(null)).isNull();
    }
}
