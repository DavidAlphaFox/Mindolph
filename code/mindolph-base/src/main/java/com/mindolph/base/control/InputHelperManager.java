package com.mindolph.base.control;

import com.github.swiftech.swstate.StateBuilder;
import com.github.swiftech.swstate.StateMachine;
import com.github.swiftech.swstate.trigger.Trigger;
import com.mindolph.base.plugin.Plugin;
import com.mindolph.base.plugin.PluginManager;
import com.mindolph.mfx.util.KeyEventUtils;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactfx.EventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * @author mindolph.com@gmail.com
 * @since 1.6
 */
public class InputHelperManager {
    private static final Logger log = LoggerFactory.getLogger(InputHelperManager.class);

    public static final String NO_HELP = "no-help";
    public static final String HELP_START = "help-start";
    public static final String HELPING = "helping";

    // trigger data for unknown condition.
    public static final String UNKNOWN_INPUT = "unknown";

    private Node node;
    private double caretX;
    private double caretY;

    private final ContextMenu menu = new ContextMenu();
    private final EventSource<Selection> selectEvent = new EventSource<>();
    private final StateMachine<String, Serializable> stateMachine;
    private final Trigger isAlphanumeric = (data, payload) -> (data instanceof Character)
            && (Character.isAlphabetic((char) data) || Character.isDigit((Character) data));
    private final Trigger isStopBackspace = (data, payload) -> KeyCode.BACK_SPACE.equals(data) && StringUtils.isBlank(payload.toString());
    private final Trigger isKeepBackspace = (data, payload) -> KeyCode.BACK_SPACE.equals(data) && !StringUtils.isBlank(payload.toString());
    private final Trigger isReturn = (data, payload) -> KeyCode.ENTER.equals(data);
    private final Trigger unknownInput = (data, payload) -> UNKNOWN_INPUT.equals(data);

    private String fileType;

    public InputHelperManager(String fileType) {
        this.fileType = fileType;
        StateBuilder<String, Serializable> stateBuilder = new StateBuilder<>();
        Trigger[] quitTriggers = stateBuilder.triggerBuilder().custom(isReturn).custom(isStopBackspace).custom(unknownInput).build();
        stateBuilder
                .state(NO_HELP)
                .in(str -> {
                    menu.hide();
                })
                .state(HELP_START).in(str -> menu.hide())
                .state(HELPING).in(str -> this.updateAndShowContextMenu(fileType, (String) str))
                .initialize(NO_HELP)
                .action("still-no-help", NO_HELP, NO_HELP, quitTriggers)
                .action("start-input-help", NO_HELP, HELP_START, stateBuilder.triggerBuilder().c(' ', '\t').custom(isAlphanumeric).build())
                .action("cancel-start", HELP_START, NO_HELP, quitTriggers)
                .action("helping", HELP_START, HELPING, isAlphanumeric)
                .action("still-start", HELP_START, HELP_START, stateBuilder.triggerBuilder().c(' ', '\t').build())
                .action("still-helping", HELPING, HELPING, stateBuilder.triggerBuilder().custom(isAlphanumeric).custom(isKeepBackspace).build())
                .action("stop-help", HELPING, NO_HELP, quitTriggers)
                .action("next-help", HELPING, HELP_START, stateBuilder.triggerBuilder().c(' ', '\t').build());
        stateMachine = new StateMachine<>(stateBuilder);
        stateMachine.startState(NO_HELP);
    }

    public void updateCaret(Node node, double x, double y) {
        this.node = node;
        this.caretX = x;
        this.caretY = y;
    }

    // TODO let Plugin decide?
    private boolean isAllowed(KeyEvent event) {
        String str = event.getText();
        return StringUtils.isAlphanumeric(str)
                || StringUtils.equalsAny(str, " ", "\r", "\t")
                || KeyCode.BACK_SPACE.equals(event.getCode());
    }

    /**
     * Consume data with payload directly.
     *
     * @param data
     * @param str
     */
    public void consume(String data, String str) {
        stateMachine.acceptWithPayload(data, str);
    }

    /**
     * Consume key press event with payload.
     *
     * @param event
     * @param str
     */
    public void consume(KeyEvent event, String str) {
        if (KeyEventUtils.isModifierKeyDown(event)) {
            return;
        }
        if (!isAllowed(event)) {
            return;
        }

        String text = event.getText();
        Object data;
        if (StringUtils.isNotBlank(text)) {
            data = text.charAt(0);
        }
        else {
            data = event.getCode();
        }
        if (stateMachine.acceptWithPayload(data, str)) {
            event.consume();
        }
    }

    private void updateAndShowContextMenu(String fileType, String input) {
        log.debug("search with: '%s'".formatted(input));

        Collection<Plugin> plugins = PluginManager.getIns().findPlugin(fileType);
        if (CollectionUtils.isEmpty(plugins)) {
            return;
        }

        List<String> keywords = plugins.stream().flatMap((Function<Plugin, Stream<String>>) plugin -> plugin.getInputHelper().getHelpWords().stream()).toList();
        log.debug("%d words in total".formatted(keywords.size()));

        if (CollectionUtils.isEmpty(keywords)) {
            return;
        }

        for (String string : keywords) {
            System.out.println(string);
        }

        // get rid of duplicates
        keywords = keywords.stream()
                .filter(StringUtils::isNotBlank)
                .filter(s -> !StringUtils.equals(s, input))
                .distinct().toList();

        List<String> filtered = StringUtils.isBlank(input) ? keywords
                : keywords.stream().filter(s -> s.startsWith(input)).toList();

        if (CollectionUtils.isEmpty(filtered)) {
            return;
        }

        log.debug("%d are selected to be candidates".formatted(filtered.size()));
        menu.getItems().clear();
        if (!filtered.isEmpty()) {
            for (String keyword : filtered) {
                MenuItem mi = new MenuItem(keyword);
                mi.setUserData(keyword);
                mi.setOnAction(event -> {
                    selectEvent.push(new Selection(input, (String) mi.getUserData()));
                });
                menu.getItems().add(mi);
            }
        }
        menu.show(node, caretX, caretY);
    }

    public void onSelected(Consumer<Selection> consumer) {
        selectEvent.subscribe(consumer);
    }

    public record Selection(String input, String selected) {
    }
}