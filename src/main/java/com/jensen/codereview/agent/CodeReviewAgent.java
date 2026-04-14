/*
 * All rights Reserved, Designed By Jensen
 * @Title:  CodeReviewAgent.java
 * @Package com.jensen.codereview.agent
 * @author: Jensen
 * @date:   2026/4/14 10:31
 * @version V1.0
 */
package com.jensen.codereview.agent;

import com.jensen.codereview.skill.base.SkillContext;
import com.jensen.codereview.skill.base.SkillResult;
import com.jensen.codereview.skill.orchestrator.SkillOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @ClassName CodeReviewAgent
 * @Description 代码审查代理
 * @Author Jensen
 * @Date 2026/4/14 10:32
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeReviewAgent {

    /**
     * 技能执行器
     */
    private final SkillOrchestrator orchestrator;

    /**
     * 聊天客户端
     */
    private final ChatClient chatClient;

    /**
     * 模型名称
     */
    @Value("${spring.ai.openai.chat.options.model:doubao-pro-32k}")
    private String modelName;

    /**
     * 是否启用 AI 总结
     */
    @Value("${code-review.ai-summary.enabled:true}")
    private boolean aiSummaryEnabled;

    /**
     * AI 总结最大 token 数
     */
    @Value("${code-review.ai-summary.max-tokens:1500}")
    private int aiSummaryMaxTokens;

    /**
     * 代码审查
     * @param codeContent 代码内容
     * @return 审查结果
     */
    public String review(String codeContent) {
        log.info("开始代码审查");
        long startTime = System.currentTimeMillis();

        // 1. 创建上下文
        SkillContext context = SkillContext.create(codeContent);

        // 2. 并行执行技能链（提升速度）
        var results = orchestrator.executeParallel(context).join();
        SkillResult aggregated = orchestrator.mergeResults(results);
        log.info("Skills 并行执行完成，耗时: {}ms", System.currentTimeMillis() - startTime);

        // 3. AI 增强总结（可配置关闭以提升速度）
        String aiSummary;
        if (aiSummaryEnabled) {
            aiSummary = generateAISummary(codeContent, aggregated);
            log.info("AI 总结完成，总耗时: {}ms", System.currentTimeMillis() - startTime);
        } else {
            aiSummary = "⚡ 快速模式：已跳过 AI 深度分析，仅展示规则审查结果";
            log.info("快速模式：跳过 AI 总结，总耗时: {}ms", System.currentTimeMillis() - startTime);
        }

        // 4. 生成最终报告
        return buildFinalReport(aggregated, aiSummary);
    }

    /**
     * AI 总结（优化版 - 快速响应）
     * @param code 源码
     * @param result 技能结果
     * @return 总结
     */
    private String generateAISummary(String code, SkillResult result) {
        try {
            long aiStartTime = System.currentTimeMillis();
            
            // 限制代码长度，避免超出 token 限制
            String codeSnippet = code.length() > 2000 ? code.substring(0, 2000) + "\n...[截断]" : code;
            
            // 简化的 Prompt，聚焦核心问题
            String prompt = String.format("""
                你是一位资深 Java 架构师。请基于以下代码和审查发现，提供简洁专业的分析：
                
                【代码片段】
                ```java
                %s
                ```
                
                【审查发现】%s
                【问题统计】总数:%d | 严重:%d | 高危:%d | 中危:%d | 低危:%d
                
                请提供：
                1. **总体评分**（0-100分）和一句话评价
                2. **Top 3 关键问题**（最严重的3个问题，简明扼要）
                3. **核心改进建议**（2-3条最重要的建议）
                4. **重构示例**（如果有严重问题，提供简短的代码改进示例）
                
                要求：简洁、专业、实用，控制在500字以内。
                """,
                    codeSnippet,
                    result.getSummary(),
                    result.getIssues().size(),
                    result.getIssues().stream().filter(i -> "CRITICAL".equals(i.getSeverity())).count(),
                    result.getIssues().stream().filter(i -> "HIGH".equals(i.getSeverity())).count(),
                    result.getIssues().stream().filter(i -> "MEDIUM".equals(i.getSeverity())).count(),
                    result.getIssues().stream().filter(i -> "LOW".equals(i.getSeverity())).count()
            );

            log.info("开始 AI 总结，Prompt 长度: {} 字符", prompt.length());
            
            ChatResponse response = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .model(modelName)
                            .temperature(0.3)
                            .maxTokens(aiSummaryMaxTokens)
                            .build())
                    .call()
                    .chatResponse();

            long aiElapsed = System.currentTimeMillis() - aiStartTime;
            log.info("AI 总结完成，耗时: {}ms", aiElapsed);

            return response != null ? response.getResult().getOutput().getText() : "AI总结生成失败";
        } catch (Exception e) {
            log.error("AI总结失败", e);
            return "基于规则审查：" + result.getSummary();
        }
    }

    /**
     * 生成最终报告（Markdown 格式）
     * @param result 技能结果
     * @param aiSummary AI总结
     * @return 最终报告
     */
    private String buildFinalReport(SkillResult result, String aiSummary) {
        StringBuilder report = new StringBuilder();
        
        // 审查概览
        report.append("## 📊 概览\n\n");
        
        long criticalCount = result.getIssues().stream().filter(i -> "CRITICAL".equals(i.getSeverity())).count();
        long highCount = result.getIssues().stream().filter(i -> "HIGH".equals(i.getSeverity())).count();
        long mediumCount = result.getIssues().stream().filter(i -> "MEDIUM".equals(i.getSeverity())).count();
        long lowCount = result.getIssues().stream().filter(i -> "LOW".equals(i.getSeverity())).count();
        
        report.append("| 指标 | 数量 |\n");
        report.append("|------|------|\n");
        report.append(String.format("| 🔴 严重问题 (CRITICAL) | %d 个 |\n", criticalCount));
        report.append(String.format("| 🟠 高危问题 (HIGH) | %d 个 |\n", highCount));
        report.append(String.format("| 🟡 中危问题 (MEDIUM) | %d 个 |\n", mediumCount));
        report.append(String.format("| 🔵 低危问题 (LOW) | %d 个 |\n", lowCount));
        report.append(String.format("| 📈 问题总数 | %d 个 |\n", result.getIssues().size()));
        report.append(String.format("| ⏱️ 审查耗时 | %d ms |\n\n", result.getExecutionTimeMs()));
        
        // AI 深度分析
        report.append("## 🧠 深度分析\n\n");
        report.append(aiSummary).append("\n\n");
        
        // 智能问题分类汇总
        if (!result.getIssues().isEmpty()) {
            report.append(buildSmartIssueSummary(result.getIssues()));
        }
        
        // 报告尾部
        report.append("---\n\n");
        report.append("专业 · 深入 · 可落地\n");
        
        return report.toString();
    }
    
    /**
     * 构建智能问题分类汇总（Markdown 格式）
     * @param issues 问题列表
     * @return 格式化后的问题汇总
     */
    private String buildSmartIssueSummary(List<SkillResult.Issue> issues) {
        StringBuilder summary = new StringBuilder();
        
        // 按严重程度分组
        Map<String, List<SkillResult.Issue>> groupedBySeverity = issues.stream()
                .collect(java.util.stream.Collectors.groupingBy(SkillResult.Issue::getSeverity));
        
        // 按类别分组
        Map<String, List<SkillResult.Issue>> groupedByCategory = issues.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        issue -> issue.getCategory() != null ? issue.getCategory() : "OTHER"
                ));
        
        summary.append("## 📋 问题分类汇总\n\n");
        
        // 只显示关键问题（CRITICAL 和 HIGH）
        List<SkillResult.Issue> criticalIssues = groupedBySeverity.getOrDefault("CRITICAL", new ArrayList<>());
        List<SkillResult.Issue> highIssues = groupedBySeverity.getOrDefault("HIGH", new ArrayList<>());
        
        if (!criticalIssues.isEmpty() || !highIssues.isEmpty()) {
            summary.append("### ⚠️ 需要立即关注的关键问题\n\n");
            
            int issueNum = 1;
            
            // 严重问题
            for (SkillResult.Issue issue : criticalIssues) {
                summary.append(String.format("#### %d. 🔴 [严重] %s\n\n", issueNum++, issue.getDescription()));
                if (issue.getLineNumber() != null && issue.getLineNumber() > 0) {
                    summary.append(String.format("- **📍 位置**: 第 %d 行\n", issue.getLineNumber()));
                }
                summary.append(String.format("- **💡 建议**: %s\n\n", issue.getFixSuggestion()));
            }
            
            // 高危问题
            for (SkillResult.Issue issue : highIssues) {
                summary.append(String.format("#### %d. 🟠 [高危] %s\n\n", issueNum++, issue.getDescription()));
                if (issue.getLineNumber() != null && issue.getLineNumber() > 0) {
                    summary.append(String.format("- **📍 位置**: 第 %d 行\n", issue.getLineNumber()));
                }
                summary.append(String.format("- **💡 建议**: %s\n\n", issue.getFixSuggestion()));
            }
        }
        
        // 按类别统计（中危和低危问题只展示统计信息）
        List<SkillResult.Issue> mediumAndLow = issues.stream()
                .filter(i -> "MEDIUM".equals(i.getSeverity()) || "LOW".equals(i.getSeverity()))
                .toList();
        
        if (!mediumAndLow.isEmpty()) {
            summary.append("### 📊 其他问题统计\n\n");
            summary.append(String.format("共 **%d** 个问题，建议逐步优化\n\n", mediumAndLow.size()));
            
            summary.append("| 问题类别 | 数量 |\n");
            summary.append("|----------|------|\n");
            
            groupedByCategory.forEach((category, categoryIssues) -> {
                long count = categoryIssues.stream()
                        .filter(i -> "MEDIUM".equals(i.getSeverity()) || "LOW".equals(i.getSeverity()))
                        .count();
                if (count > 0) {
                    String categoryName = getCategoryDisplayName(category);
                    summary.append(String.format("| %s | %d 个 |\n", categoryName, count));
                }
            });
            
            summary.append("\n> 💡 **提示**: 以上问题已在「架构师深度分析」中给出综合改进建议\n");
        }
        
        return summary.toString();
    }
    
    /**
     * 获取类别显示名称
     * @param category 类别代码
     * @return 显示名称
     */
    private String getCategoryDisplayName(String category) {
        return switch (category) {
            case "STYLE" -> "代码风格";
            case "SECURITY" -> "安全隐患";
            case "BUG" -> "潜在Bug";
            case "PERFORMANCE" -> "性能问题";
            case "BEST_PRACTICE" -> "最佳实践";
            case "COMPLEXITY" -> "复杂度";
            case "DOCUMENTATION" -> "文档注释";
            case "QUALITY" -> "代码质量";
            default -> "其他问题";
        };
    }
    
    /**
     * 文本居中
     * @param text 文本
     * @param width 宽度
     * @return 居中后的文本
     */
    private String centerText(String text, int width) {
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text + " ".repeat(Math.max(0, width - padding - text.length()));
    }
}
