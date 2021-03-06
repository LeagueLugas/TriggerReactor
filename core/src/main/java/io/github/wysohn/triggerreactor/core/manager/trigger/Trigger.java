package io.github.wysohn.triggerreactor.core.manager.trigger;

import io.github.wysohn.triggerreactor.core.bridge.entity.IPlayer;
import io.github.wysohn.triggerreactor.core.main.TriggerReactorCore;
import io.github.wysohn.triggerreactor.core.manager.trigger.AbstractTriggerManager.TriggerInitFailedException;
import io.github.wysohn.triggerreactor.core.script.interpreter.Executor;
import io.github.wysohn.triggerreactor.core.script.interpreter.Interpreter;
import io.github.wysohn.triggerreactor.core.script.interpreter.InterpreterException;
import io.github.wysohn.triggerreactor.core.script.interpreter.Placeholder;
import io.github.wysohn.triggerreactor.core.script.lexer.Lexer;
import io.github.wysohn.triggerreactor.core.script.lexer.LexerException;
import io.github.wysohn.triggerreactor.core.script.parser.Node;
import io.github.wysohn.triggerreactor.core.script.parser.Parser;
import io.github.wysohn.triggerreactor.core.script.parser.ParserException;
import io.github.wysohn.triggerreactor.core.script.warning.Warning;
import io.github.wysohn.triggerreactor.tools.StringUtils;
import io.github.wysohn.triggerreactor.tools.timings.Timings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public abstract class Trigger implements Cloneable {
    protected final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    protected final File file;
    private static final ExecutorService asyncPool = Executors.newCachedThreadPool();

    protected String triggerName;
    protected String script;

    protected Node root;
    protected Map<String, Executor> executorMap;
    protected Map<String, Placeholder> placeholderMap;
    protected Map<Object, Object> gvarMap;

    private boolean sync = false;

    /**
     * This constructor <b>does not</b> initialize the fields. It is essential to call {@link #init()} method
     * in order to make the Trigger work properly. If you want to create a Trigger with customized
     * behavior, it's not necessary to call {@link #init()} but need to override {@link #initInterpreter(Map)},
     * {@link #startInterpretation(Object, Map, Interpreter, boolean)}, or {@link #activate(Object, Map)} method as your need
     *
     * @param script
     */
    public Trigger(String triggerName, File file, String script) {
        super();

        this.file = file;

        this.triggerName = triggerName;
        this.script = script;
    }

    /**
     * Get File instance pointing to the Trigger file
     *
     * @return
     */
    public File getFile() {
        return file;
    }

    /**
     * Get this trigger's name.
     *
     * @return
     */
    public String getTriggerName() {
        return triggerName;
    }

    /**
     * Get unique id to be used as fully qualified name for the Timings System.
     * Override to alter the id.
     *
     * @return the id.
     */
    protected String getTimingId() {
        return StringUtils.dottedPath(getClass().getSimpleName(), triggerName);
    }

    public void setTriggerName(String triggerName) {
        this.triggerName = triggerName;
    }

    /**
     * @throws IOException                low level exception from Lexer
     * @throws LexerException             throws if lexical analysis failed
     * @throws ParserException            throws if parsing failed
     * @throws TriggerInitFailedException
     */
    public void init() throws TriggerInitFailedException {
        try {
            Charset charset = StandardCharsets.UTF_8;

            Lexer lexer = new Lexer(script, charset);
            Parser parser = new Parser(lexer);

            root = parser.parse(true);
            List<Warning> warnings = parser.getWarnings();

            AbstractTriggerManager.reportWarnings(warnings, this);
            executorMap = TriggerReactorCore.getInstance().getExecutorManager().getBackedMap();
            placeholderMap = TriggerReactorCore.getInstance().getPlaceholderManager().getBackedMap();
            gvarMap = TriggerReactorCore.getInstance().getVariableManager().getGlobalVariableAdapter();
        } catch (Exception ex) {
            throw new TriggerInitFailedException("Failed to initialize Trigger [" + this.getClass().getSimpleName()
                    + " -- " + triggerName + "]!", ex);
        }
    }

    /**
     * get the actual plain code of this trigger.
     *
     * @return
     */
    public String getScript() {
        return script;
    }

    public void setScript(String script) throws TriggerInitFailedException {
        if (script == null)
            throw new RuntimeException("script cannot be null.");

        this.script = script;
        try {
            init();
        } catch (TriggerInitFailedException e) {
            throw e;
        }
    }

    /**
     * Check if this Trigger is sync mode.
     *
     * @return
     */
    public boolean isSync() {
        return sync;
    }

    /**
     * Set this Trigger's sync mode.
     *
     * @param sync
     */
    public void setSync(boolean sync) {
        this.sync = sync;
    }

    /**
     * Start this trigger. Variables in scriptVars may be overridden if it has same name as
     * the name of fields of Event class.
     *
     * @param e          the Event associated with this Trigger
     * @param scriptVars the temporary local variables
     * @return true if activated; false if on cooldown
     */
    public boolean activate(Object e, Map<String, Object> scriptVars) {
        if (checkCooldown(e)) {
            return false;
        }

        scriptVars.put("event", e);
        scriptVars.putAll(TriggerReactorCore.getInstance().getSharedVars());
        Map<String, Object> customVars = TriggerReactorCore.getInstance().getCustomVarsForTrigger(e);
        if (customVars != null)
            scriptVars.putAll(customVars);

        Interpreter interpreter = initInterpreter(scriptVars);

        startInterpretation(e, scriptVars, interpreter, isSync());
        return true;
    }

    /**
     * @param e
     * @return true if cooldown; false if not cooldown or 'e' is not a compatible type
     */
    protected boolean checkCooldown(Object e) {
        IPlayer iPlayer = TriggerReactorCore.getInstance().extractPlayerFromContext(e);

        if (iPlayer != null) {
            UUID uuid = iPlayer.getUniqueId();

            if (uuid != null) {
                Long end = cooldowns.get(uuid);
                return end != null && System.currentTimeMillis() < end;
            }
        }
        return false;
    }

    /**
     * Create interpreter with appropriate options and variables.
     *
     * @param scriptVars
     * @return
     */
    protected Interpreter initInterpreter(Map<String, Object> scriptVars) {
        Interpreter interpreter = new Interpreter(root);
        interpreter.setTaskSupervisor(TriggerReactorCore.getInstance());
        interpreter.setExecutorMap(executorMap);
        interpreter.setPlaceholderMap(placeholderMap);
        interpreter.setGvars(gvarMap);
        interpreter.setVars(scriptVars);
        interpreter.setSelfReference(TriggerReactorCore.getInstance().getSelfReference());

        interpreter.setSync(isSync());

        return interpreter;
    }

    /**
     * Start interpreting the code.
     *
     * @param e           The Event associated with this Trigger
     * @param scriptVars  temporary variables
     * @param interpreter The Interpreter
     * @param sync        set it true will make this method run in the thread that
     *                    has called this method. This is useful when this trigger has to cancel an Event;
     *                    set it to false will let it run in separate thread. This is more efficient if you
     *                    only need to read data from Event and never interact with it.
     */
    protected void startInterpretation(Object e, Map<String, Object> scriptVars, Interpreter interpreter, boolean sync) {
        Callable<Void> call = new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try (Timings.Timing t = Timings.getTiming(getTimingId()).begin(sync)) {
                    start(t, e, scriptVars, interpreter, sync);
                } catch (Exception ex) {
                    TriggerReactorCore.getInstance().handleException(e, new Exception(
                            "Trigger [" + getTriggerName() + "] produced an error!", ex));
                }
                return null;
            }
        };

        if (sync) {
            if (TriggerReactorCore.getInstance().isServerThread()) {
                try {
                    call.call();
                } catch (Exception e1) {

                }
            } else {
                Future<Void> future = TriggerReactorCore.getInstance().callSyncMethod(call);
                try {
                    future.get(3, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException e1) {

                } catch (TimeoutException e1) {
                    TriggerReactorCore.getInstance().handleException(e, new RuntimeException(
                            "Took too long to process Trigger [" + getTriggerName() + "]! Is the server lagging?",
                            e1));
                }
            }
        } else {
            asyncPool.submit(call);
        }
    }

    /**
     * The actual execution part. The Trigger can be sync/async depends on which thread invokes this method.
     *
     * @param e
     * @param scriptVars
     * @param interpreter
     * @param sync
     * @param timing
     */
    protected void start(Timings.Timing timing, Object e, Map<String, Object> scriptVars, Interpreter interpreter,
                         boolean sync) {
        try {
            interpreter.startWithContextAndInterrupter(e,
                    TriggerReactorCore.getInstance().createInterrupter(e, interpreter, cooldowns),
                    timing);
        } catch (InterpreterException ex) {
            TriggerReactorCore.getInstance().handleException(e,
                    new Exception("Could not finish interpretation for [" + getTriggerName() + "]!", ex));
        }
    }

    /**
     * The actual execution part. The Trigger can be sync/async depends on which thread invokes this method.
     *
     * @param e
     * @param scriptVars
     * @param interpreter
     * @param sync
     */
    protected void start(Object e, Map<String, Object> scriptVars, Interpreter interpreter, boolean sync) {
        start(Timings.LIMBO, e, scriptVars, interpreter, sync);
    }

    @Override
    public abstract Trigger clone();

    @Override
    public String toString() {
        return "[" + getClass().getSimpleName() + "=" + getTriggerName() + " sync=" + sync + "]";
    }
}