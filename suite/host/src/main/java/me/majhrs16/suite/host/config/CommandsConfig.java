package me.majhrs16.suite.host.config;

import java.util.List;
import java.util.Map;

/**
 * Configuración del sistema de comandos dinámico (commands.yml v2).
 */
public final class CommandsConfig {

    private final String baseName;
    private final List<String> aliases;
    private final Map<String, ActionDef> actions;
    private final Map<String, CommandNode> commands;

    public CommandsConfig(String baseName, List<String> aliases,
                          Map<String, ActionDef> actions,
                          Map<String, CommandNode> commands) {
        this.baseName = baseName;
        this.aliases = aliases;
        this.actions = actions;
        this.commands = commands;
    }

    public String baseName() { return baseName; }
    public List<String> aliases() { return aliases; }
    public Map<String, ActionDef> actions() { return actions; }
    public Map<String, CommandNode> commands() { return commands; }

    /** Definición de una acción atómica reutilizable. */
    public static final class ActionDef {
        private final String description;
        private final String permission;
        private final String adminPermission;
        private final List<ArgDef> args;
        private final boolean confirm;
        private final String execute;

        public ActionDef(String description, String permission, String adminPermission,
                         List<ArgDef> args, boolean confirm, String execute) {
            this.description = description;
            this.permission = permission;
            this.adminPermission = adminPermission;
            this.args = args;
            this.confirm = confirm;
            this.execute = execute;
        }

        public String description() { return description; }
        public String permission() { return permission; }
        public String adminPermission() { return adminPermission; }
        public List<ArgDef> args() { return args; }
        public boolean confirm() { return confirm; }
        public String execute() { return execute; }
    }

    /** Definición de un argumento de acción. */
    public static final class ArgDef {
        private final String name;
        private final String type;
        private final String description;
        private final String defaultValue;

        public ArgDef(String name, String type, String description, String defaultValue) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.defaultValue = defaultValue;
        }

        public String name() { return name; }
        public String type() { return type; }
        public String description() { return description; }
        public String defaultValue() { return defaultValue; }
    }

    /** Nodo del árbol de comandos. */
    public static final class CommandNode {
        private final String description;
        private final String permission;
        private final String ref;
        private final Map<String, String> fixedArgs;
        private final String argBinding;
        private final Map<String, CommandNode> children;

        public CommandNode(String description, String permission, String ref,
                           Map<String, String> fixedArgs, String argBinding,
                           Map<String, CommandNode> children) {
            this.description = description;
            this.permission = permission;
            this.ref = ref;
            this.fixedArgs = fixedArgs;
            this.argBinding = argBinding;
            this.children = children;
        }

        public String description() { return description; }
        public String permission() { return permission; }
        public String ref() { return ref; }
        public Map<String, String> fixedArgs() { return fixedArgs; }
        public String argBinding() { return argBinding; }
        public Map<String, CommandNode> children() { return children; }
    }
}