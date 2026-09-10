package org.gitee.jmeter.ai.agent.skills;

import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loader for agent skills.
 * Skills are markdown files (SKILL.md) that teach the agent how to use
 * specific tools or perform certain tasks.
 *
 * Skills are loaded from two locations:
 * 1. Built-in skills: {jmeter.bin}/jmeter-agent/skills/{skill_name}/SKILL.md
 * 2. Workspace skills: {workspace}/skills/{skill_name}/SKILL.md
 */
public class SkillsLoader {
    private static final Logger log = LoggerFactory.getLogger(SkillsLoader.class);

    private final Path workspaceSkillsDir;
    private final Path builtinSkillsDir;
    public SkillsLoader(Path workspaceDir) {
        this.workspaceSkillsDir = workspaceDir.resolve("skills");
        // Built-in skills are now in JMeter's bin directory
        this.builtinSkillsDir = getBuiltinSkillsDirectory();

        ensureBuiltinSkillsDirectory();
        ensureWorkspaceSkillsDirectory();
    }

    /**
     * List all available skills.
     *
     * @param filterUnavailable If true, filter out skills with unmet requirements
     * @return List of skill info
     */
    public List<SkillInfo> listSkills(boolean filterUnavailable) {
        List<SkillInfo> skills = new ArrayList<>();

        // Load workspace skills (highest priority)
        skills.addAll(loadWorkspaceSkills());

        // Load built-in skills from filesystem ({jmeter.home}/bin/jmeter-agent/skills)
        skills.addAll(loadBuiltinSkills());

        // Remove duplicates (workspace takes priority)
        skills = removeDuplicates(skills);

        // Filter by availability if requested
        if (filterUnavailable) {
            return skills.stream()
                    .filter(SkillInfo::isAvailable)
                    .toList();
        }

        return skills;
    }

    /**
     * Get skills marked as always=true that meet requirements.
     *
     * @return List of always skill names
     */
    public List<String> getAlwaysSkills() {
        List<String> alwaysSkills = listSkills(true).stream()
                .filter(SkillInfo::isAlways)
                .map(SkillInfo::getName)
                .toList();
        log.info("Found {} always skills: {}", alwaysSkills.size(), alwaysSkills);
        return alwaysSkills;
    }

    /**
     * Load a skill by name.
     * Workspace skills take priority over built-in skills.
     *
     * @param name Skill name
     * @return Skill content or null if not found
     */
    public String loadSkill(String name) {
        // Try workspace first
        String content = loadWorkspaceSkill(name);
        if (content != null) {
            return content;
        }

        // Try built-in
        return loadBuiltinSkill(name);
    }

    /**
     * Load specific skills for inclusion in agent context.
     *
     * @param skillNames List of skill names to load
     * @return Formatted skills content
     */
    public String loadSkillsForContext(List<String> skillNames) {
        List<String> parts = new ArrayList<>();

        for (String name : skillNames) {
            String content = loadSkill(name);
            if (content != null) {
                content = stripFrontmatter(content);
                parts.add("### Skill: " + name + "\n\n" + content);
            } else {
                log.warn("Failed to load skill for context: {}", name);
            }
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * Build a summary of all skills for progressive loading.
     * The agent can read the full skill content using tools when needed.
     *
     * @return XML-formatted skills summary
     */
    public String buildSkillsSummary() {
        List<SkillInfo> allSkills = listSkills(false);
        if (allSkills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<skills>\n");

        for (SkillInfo skill : allSkills) {
            sb.append("  <skill available=\"").append(skill.isAvailable()).append("\">\n");
            sb.append("    <name>").append(escapeXml(skill.getName())).append("</name>\n");
            sb.append("    <description>").append(escapeXml(skill.getDescription())).append("</description>\n");
            sb.append("    <location>").append(escapeXml(skill.getPath())).append("</location>\n");

            if (!skill.isAvailable()) {
                SkillMetadata meta = getSkillMetadata(skill.getName());
                if (meta != null && !meta.getMissingRequirements().isEmpty()) {
                    sb.append("    <requires>").append(escapeXml(meta.getMissingRequirements())).append("</requires>\n");
                }
            }

            sb.append("  </skill>\n");
        }

        sb.append("</skills>");
        return sb.toString();
    }

    // Private methods

    private Path getBuiltinSkillsDirectory() {
        try {
            Path runtimeSkillsDir = WorkspacePaths.builtinSkillsDir();
            if (Files.exists(runtimeSkillsDir)) {
                return runtimeSkillsDir;
            }
        } catch (Exception e) {
            log.debug("Could not determine JMeter home directory", e);
        }

        log.warn("Built-in skills directory not found: {}", WorkspacePaths.builtinSkillsDir());
        return null;
    }

    private void ensureBuiltinSkillsDirectory() {
        if (builtinSkillsDir == null) {
            log.debug("Built-in skills directory not available (JMeter home not found)");
            return;
        }

        try {
            if (!Files.exists(builtinSkillsDir)) {
                Files.createDirectories(builtinSkillsDir);
                log.info("Created built-in skills directory: {}", builtinSkillsDir);
            }
        } catch (IOException e) {
            log.warn("Failed to create built-in skills directory: {}", builtinSkillsDir, e);
        }
    }

    private void ensureWorkspaceSkillsDirectory() {
        try {
            if (!Files.exists(workspaceSkillsDir)) {
                Files.createDirectories(workspaceSkillsDir);
                log.info("Created workspace skills directory: {}", workspaceSkillsDir);
            }
        } catch (IOException e) {
            log.warn("Failed to create workspace skills directory", e);
        }
    }

    private List<SkillInfo> loadWorkspaceSkills() {
        List<SkillInfo> skills = new ArrayList<>();

        if (!Files.exists(workspaceSkillsDir)) {
            return skills;
        }

        try (Stream<Path> dirs = Files.list(workspaceSkillsDir)) {
            dirs.filter(Files::isDirectory)
                    .forEach(skillDir -> {
                        Path skillFile = skillDir.resolve("SKILL.md");
                        if (Files.exists(skillFile)) {
                            SkillMetadata meta = parseSkillMetadata(skillFile);
                            Instant lastMod = Instant.ofEpochMilli(skillFile.toFile().lastModified());
                            boolean available = checkRequirements(meta);

                            skills.add(new SkillInfo(
                                    skillDir.getFileName().toString(),
                                    skillFile.toString(),
                                    SkillInfo.SkillSource.WORKSPACE,
                                    meta.getDescription(),
                                    meta.isAlways(),
                                    available,
                                    lastMod
                            ));
                        }
                    });
        } catch (IOException e) {
            log.warn("Error loading workspace skills", e);
        }

        return skills;
    }

    private List<SkillInfo> loadBuiltinSkills() {
        List<SkillInfo> skills = new ArrayList<>();

        // Load from JMeter's bin/jmeter-agent/skills directory
        if (builtinSkillsDir != null && Files.exists(builtinSkillsDir)) {
            loadBuiltinSkillsFromFilesystem(builtinSkillsDir, skills);
        }

        return skills;
    }

    private void loadBuiltinSkillsFromFilesystem(Path skillsDir, List<SkillInfo> skills) {
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                    .forEach(skillDir -> {
                        Path skillFile = skillDir.resolve("SKILL.md");
                        if (Files.exists(skillFile)) {
                            try {
                                String content = Files.readString(skillFile);
                                SkillMetadata meta = parseSkillMetadata(content);
                                boolean available = checkRequirements(meta);

                                skills.add(new SkillInfo(
                                        skillDir.getFileName().toString(),
                                        skillFile.toString(),
                                        SkillInfo.SkillSource.BUILTIN,
                                        meta.getDescription(),
                                        meta.isAlways(),
                                        available,
                                        Instant.ofEpochMilli(skillFile.toFile().lastModified())
                                ));
                            } catch (IOException e) {
                                log.warn("Error reading skill file: {}", skillFile, e);
                            }
                        }
                    });
        } catch (IOException e) {
            log.warn("Error loading built-in skills from filesystem", e);
        }
    }

    private String loadWorkspaceSkill(String name) {
        Path skillFile = workspaceSkillsDir.resolve(name).resolve("SKILL.md");
        if (!Files.exists(skillFile)) {
            return null;
        }

        try {
            return Files.readString(skillFile);
        } catch (IOException e) {
            log.warn("Error loading workspace skill: {}", name, e);
            return null;
        }
    }

    private String loadBuiltinSkill(String name) {
        // Load from JMeter's bin/jmeter-agent/skills directory
        if (builtinSkillsDir != null) {
            Path skillFile = builtinSkillsDir.resolve(name).resolve("SKILL.md");
            if (Files.exists(skillFile)) {
                try {
                    return Files.readString(skillFile);
                } catch (IOException e) {
                    log.warn("Error loading built-in skill from file: {}", skillFile, e);
                }
            } else {
                log.warn("Built-in skill file not found: {}", skillFile);
            }
        }
        return null;
    }

    private List<SkillInfo> removeDuplicates(List<SkillInfo> skills) {
        return skills.stream()
                .collect(java.util.stream.Collectors.toMap(
                        SkillInfo::getName,
                        skill -> skill,
                        (existing, replacement) -> existing // Keep first (workspace priority)
                ))
                .values()
                .stream()
                .toList();
    }

    private SkillMetadata parseSkillMetadata(Path skillFile) {
        try {
            String content = Files.readString(skillFile);
            return parseSkillMetadata(content);
        } catch (IOException e) {
            return new SkillMetadata();
        }
    }

    private SkillMetadata parseSkillMetadata(String content) {
        SkillMetadata meta = new SkillMetadata();

        if (content == null || !content.startsWith("---")) {
            return meta;
        }

        // Parse YAML frontmatter (support all Unicode line endings: \R matches LF, CRLF, CR, etc.)
        Pattern frontmatterPattern = Pattern.compile("^---\\R(.*?)\\R---", Pattern.DOTALL);
        Matcher matcher = frontmatterPattern.matcher(content);
        if (matcher.find()) {
            String frontmatter = matcher.group(1);
            for (String line : frontmatter.split("\\R")) {
                int colonPos = line.indexOf(':');
                if (colonPos > 0) {
                    String key = line.substring(0, colonPos).trim();
                    String value = line.substring(colonPos + 1).trim().replaceAll("^\"|\"$", "");

                    switch (key) {
                        case "name" -> meta.setName(value);
                        case "description" -> meta.setDescription(value);
                        case "always" -> meta.setAlways(Boolean.parseBoolean(value));
                        case "requires" -> parseRequires(meta, value);
                    }
                }
            }
        }

        return meta;
    }

    private void parseRequires(SkillMetadata meta, String value) {
        // Simple JSON parsing for requires field
        // Format: {"bins": ["git"], "env": ["API_KEY"]}
        try {
            value = value.trim();
            if (value.startsWith("{") && value.endsWith("}")) {
                value = value.substring(1, value.length() - 1);
                String[] parts = value.split(",");
                for (String part : parts) {
                    String[] kv = part.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim().replaceAll("^\"|\"$", "");
                        String vals = kv[1].trim().replaceAll("^\\[|\\]$", "");
                        for (String v : vals.split(",")) {
                            v = v.trim().replaceAll("^\"|\"$", "");
                            if (!v.isEmpty()) {
                                if ("bins".equals(key)) {
                                    meta.addRequiredBinary(v);
                                } else if ("env".equals(key)) {
                                    meta.addRequiredEnvVar(v);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse requires: {}", value);
        }
    }

    private boolean checkRequirements(SkillMetadata meta) {
        // Check required binaries
        for (String binary : meta.getRequiredBinaries()) {
            if (!isBinaryAvailable(binary)) {
                return false;
            }
        }

        // Check required environment variables
        for (String envVar : meta.getRequiredEnvVars()) {
            if (System.getenv(envVar) == null) {
                return false;
            }
        }

        return true;
    }

    private boolean isBinaryAvailable(String binary) {
        try {
            Process process = Runtime.getRuntime().exec(
                    System.getProperty("os.name").toLowerCase().contains("win")
                            ? new String[]{"where", binary}
                            : new String[]{"which", binary}
            );
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private SkillMetadata getSkillMetadata(String name) {
        String content = loadSkill(name);
        if (content != null) {
            return parseSkillMetadata(content);
        }
        return null;
    }

    private String stripFrontmatter(String content) {
        if (content.startsWith("---")) {
            // \R matches any Unicode line break (LF, CRLF, CR, etc.)
            Pattern pattern = Pattern.compile("^---\\R.*?\\R---", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return content.substring(matcher.end()).trim();
            }
        }
        return content;
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
