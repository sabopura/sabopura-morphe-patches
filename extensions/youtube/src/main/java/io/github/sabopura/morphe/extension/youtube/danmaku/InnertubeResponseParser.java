package io.github.sabopura.morphe.extension.youtube.danmaku;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses the structured comment and live-chat payloads returned by YouTube Innertube. */
final class InnertubeResponseParser {
    static final int MEMBERSHIP_COLOR = 0xFF2BA640;
    static final int PAID_FALLBACK_COLOR = 0xFFFFCA28;

    private InnertubeResponseParser() {
    }

    static InitialState parseInitial(String json) throws JSONException {
        return parseInitialRoot(new JSONObject(json));
    }

    static InitialState parseWatchPage(String html) throws JSONException {
        return parseInitialRoot(extractInitialData(html));
    }

    private static InitialState parseInitialRoot(JSONObject root) {
        LiveRendererCandidate live = new LiveRendererCandidate();
        findLiveRenderer(root, "$", live);
        return new InitialState(
                findCommentPanelContinuation(root),
                live.continuation,
                live.replay,
                containsPremiereMarker(root)
        );
    }

    static PlayerInfo parsePlayer(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject details = root.optJSONObject("videoDetails");
        boolean liveNow = details != null && details.optBoolean("isLive", false);
        boolean liveContent = details != null && (liveNow
                || details.optBoolean("isLiveContent", false));
        boolean upcoming = details != null && details.optBoolean("isUpcoming", false);
        long startEpochMs = -1;
        JSONObject microformat = root.optJSONObject("microformat");
        if (microformat != null) {
            JSONObject renderer = microformat.optJSONObject("playerMicroformatRenderer");
            JSONObject liveDetails = renderer == null
                    ? null : renderer.optJSONObject("liveBroadcastDetails");
            if (liveDetails != null) {
                liveContent = true;
                startEpochMs = parseIsoEpochMs(liveDetails.optString("startTimestamp", ""));
                liveNow = liveNow || liveDetails.optBoolean("isLiveNow", false);
                upcoming = upcoming || (!liveNow
                        && liveDetails.optString("startTimestamp", "").length() > 0
                        && liveDetails.optString("endTimestamp", "").isEmpty()
                        && startEpochMs > System.currentTimeMillis());
            }
        }
        return new PlayerInfo(liveContent, liveNow, upcoming,
                containsPremiereMarker(root), startEpochMs);
    }

    static RegularPage parseRegularPage(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        Map<String, CommentData> comments = new LinkedHashMap<>();
        collectRegularComments(root, comments);

        String continuation = findRegularItemsContinuation(root);
        if (continuation == null) {
            continuation = findCommentItemSectionContinuation(root);
        }
        String sortContinuation = findSortContinuation(root);
        JSONObject continuationContents = root.optJSONObject("continuationContents");
        if (continuation == null && continuationContents != null) {
            String[] legacyContainers = {
                    "sectionListContinuation",
                    "itemSectionContinuation"
            };
            for (String containerName : legacyContainers) {
                JSONObject section = continuationContents.optJSONObject(containerName);
                if (section == null) continue;
                continuation = firstContinuation(section.optJSONArray("continuations"));
                if (continuation == null) {
                    continuation = mainContinuationFromItems(section.optJSONArray("contents"));
                }
                if (continuation != null) break;
            }
        }
        if (sortContinuation != null && comments.isEmpty()) continuation = sortContinuation;
        return new RegularPage(new ArrayList<>(comments.values()), continuation,
                containsCommentsDisabledMessage(root));
    }

    static LivePage parseLivePage(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject continuationContents = root.optJSONObject("continuationContents");
        JSONObject live = continuationContents == null
                ? null : continuationContents.optJSONObject("liveChatContinuation");
        List<CommentData> comments = new ArrayList<>();
        long lastOffsetMs = -1;
        if (live != null) {
            JSONArray actions = live.optJSONArray("actions");
            if (actions != null) {
                for (int index = 0; index < actions.length(); index++) {
                    JSONObject action = actions.optJSONObject(index);
                    if (action == null) continue;
                    JSONObject replay = action.optJSONObject("replayChatItemAction");
                    if (replay != null) {
                        long inheritedOffset = parseLong(replay, "videoOffsetTimeMsec", -1);
                        if (inheritedOffset >= 0) lastOffsetMs = inheritedOffset;
                        JSONArray nestedActions = replay.optJSONArray("actions");
                        if (nestedActions == null) continue;
                        for (int nestedIndex = 0; nestedIndex < nestedActions.length(); nestedIndex++) {
                            CommentData parsed = parseLiveAction(
                                    nestedActions.optJSONObject(nestedIndex), inheritedOffset);
                            if (parsed != null) comments.add(parsed);
                        }
                    } else {
                        CommentData parsed = parseLiveAction(action, -1);
                        if (parsed != null) comments.add(parsed);
                    }
                }
            }
        }

        Continuation continuation = parseLiveContinuation(
                live == null ? null : live.optJSONArray("continuations"));
        return new LivePage(comments, continuation.token, continuation.timeoutMs, lastOffsetMs);
    }

    static ReplayBootstrap parseReplayBootstrap(String htmlOrJson) throws JSONException {
        JSONObject root = htmlOrJson.trim().startsWith("{")
                ? new JSONObject(htmlOrJson) : extractInitialData(htmlOrJson);
        String allMessages = findAllMessagesContinuation(root);
        LiveRendererCandidate live = new LiveRendererCandidate();
        findLiveRenderer(root, "$", live);
        return new ReplayBootstrap(allMessages != null ? allMessages : live.continuation);
    }

    private static CommentData parseLiveAction(JSONObject action, long inheritedOffsetMs) {
        if (action == null) return null;
        JSONObject add = action.optJSONObject("addChatItemAction");
        if (add == null) return null;
        JSONObject item = add.optJSONObject("item");
        if (item == null) return null;

        String[] legacyTypes = {
                "liveChatTextMessageRenderer",
                "liveChatPaidMessageRenderer",
                "liveChatMembershipItemRenderer"
        };
        for (String type : legacyTypes) {
            JSONObject renderer = item.optJSONObject(type);
            if (renderer == null) continue;
            RichCommentText content = richTextFromRuns(renderer.optJSONObject("message"));
            if (content.isEmpty()) {
                content = richTextFromRuns(renderer.optJSONObject("headerSubtext"));
            }
            if (content.isEmpty()) return null;
            long timestampUsec = parseLong(renderer, "timestampUsec", -1);
            long ownOffset = parseLong(renderer, "videoOffsetTimeMsec", -1);
            String fallback = content.fallbackText();
            return new CommentData(
                    renderer.optString("id", add.optString("clientId", fallback)),
                    content,
                    timestampUsec < 0 ? -1 : timestampUsec / 1000L,
                    ownOffset >= 0 ? ownOffset : inheritedOffsetMs,
                    highlightColor(type, renderer)
            );
        }

        JSONObject element = item.optJSONObject("elementRenderer");
        JSONObject newElement = element == null ? null : element.optJSONObject("newElement");
        JSONObject type = newElement == null ? null : newElement.optJSONObject("type");
        JSONObject component = type == null ? null : type.optJSONObject("componentType");
        JSONObject models = component == null ? null : component.optJSONObject("model");
        if (models == null) return null;

        String[] modelTypes = {
                "liveChatTextMessageModel",
                "liveChatPaidMessageModel",
                "liveChatMembershipItemModel"
        };
        for (String modelType : modelTypes) {
            JSONObject model = models.optJSONObject(modelType);
            if (model == null) continue;
            RichCommentText content = findModernLiveContent(model.optJSONObject("messageData"));
            if (content.isEmpty()) return null;
            JSONObject compatibility = element.optJSONObject("compatibilityOptions");
            String id = compatibility == null ? "" : compatibility.optString("liveChatId", "");
            if (id.isEmpty()) id = add.optString("clientId", content.fallbackText());
            int color = modelType.equals("liveChatMembershipItemModel") ? MEMBERSHIP_COLOR
                    : modelType.equals("liveChatPaidMessageModel") ? PAID_FALLBACK_COLOR : 0;
            return new CommentData(id, content, -1, inheritedOffsetMs, color);
        }
        return null;
    }

    /** Super Chat body colour, or YouTube's membership green; 0 keeps the default colour. */
    static int highlightColor(String rendererType, JSONObject renderer) {
        if ("liveChatMembershipItemRenderer".equals(rendererType)) return MEMBERSHIP_COLOR;
        if (!"liveChatPaidMessageRenderer".equals(rendererType) || renderer == null) return 0;
        long value = parseLong(renderer, "bodyBackgroundColor", -1);
        if (value < 0) value = parseLong(renderer, "headerBackgroundColor", -1);
        if (value < 0) return PAID_FALLBACK_COLOR;
        int argb = (int) value;
        // Lift very dark Super Chat tiers so outlined text stays readable over video.
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        if (red * 299 + green * 587 + blue * 114 < 90_000) return PAID_FALLBACK_COLOR;
        return 0xFF000000 | (argb & 0x00FFFFFF);
    }

    private static RichCommentText findModernLiveContent(JSONObject messageData) {
        if (messageData == null) return RichCommentText.plain("");
        JSONObject attributed = messageData.optJSONObject("attributedTextData");
        if (attributed != null) {
            JSONObject contentText = attributed.optJSONObject("contentText");
            if (contentText != null) {
                RichCommentText content = attributedText(contentText).normalized();
                if (!content.isEmpty()) return content;
            }
        }
        JSONObject contentText = messageData.optJSONObject("contentText");
        if (contentText != null) {
            RichCommentText content = attributedText(contentText).normalized();
            if (!content.isEmpty()) return content;
        }
        return RichCommentText.plain("");
    }

    private static void collectRegularComments(Object node, Map<String, CommentData> result) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject entity = object.optJSONObject("commentEntityPayload");
            if (entity != null) {
                JSONObject properties = entity.optJSONObject("properties");
                JSONObject content = properties == null ? null : properties.optJSONObject("content");
                RichCommentText richText = content == null
                        ? RichCommentText.plain("") : attributedText(content).normalized();
                String id = properties == null ? "" : properties.optString("commentId", "");
                if (!richText.isEmpty()) {
                    if (id.isEmpty()) id = entity.optString("key", richText.fallbackText());
                    result.putIfAbsent(id, new CommentData(id, richText, -1, -1));
                }
            }

            JSONObject renderer = object.optJSONObject("commentRenderer");
            if (renderer != null) {
                RichCommentText richText = richTextFromRuns(
                        renderer.optJSONObject("contentText")).normalized();
                String id = renderer.optString("commentId", richText.fallbackText());
                if (!richText.isEmpty()) {
                    result.putIfAbsent(id, new CommentData(id, richText, -1, -1));
                }
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectRegularComments(object.opt(keys.next()), result);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                collectRegularComments(array.opt(index), result);
            }
        }
    }

    private static JSONObject extractInitialData(String html) throws JSONException {
        String[] markers = {
                "var ytInitialData =",
                "window[\"ytInitialData\"] =",
                "ytInitialData ="
        };
        int searchFrom = -1;
        for (String marker : markers) {
            int markerIndex = html.indexOf(marker);
            if (markerIndex >= 0) {
                searchFrom = markerIndex + marker.length();
                break;
            }
        }
        if (searchFrom < 0) throw new JSONException("ytInitialData marker missing");
        int start = html.indexOf('{', searchFrom);
        if (start < 0) throw new JSONException("ytInitialData object missing");

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < html.length(); index++) {
            char value = html.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return new JSONObject(html.substring(start, index + 1));
            }
        }
        throw new JSONException("ytInitialData object incomplete");
    }

    private static String findRegularItemsContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String[] commandNames = {
                    "reloadContinuationItemsCommand",
                    "appendContinuationItemsAction"
            };
            for (String commandName : commandNames) {
                JSONObject command = object.optJSONObject(commandName);
                String continuation = mainContinuationFromItems(
                        command == null ? null : command.optJSONArray("continuationItems"));
                if (continuation != null) return continuation;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (isCommentLocalContinuationContainer(key)
                        || "reloadContinuationItemsCommand".equals(key)
                        || "appendContinuationItemsAction".equals(key)) {
                    continue;
                }
                String continuation = findRegularItemsContinuation(object.opt(key));
                if (continuation != null) return continuation;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                String continuation = findRegularItemsContinuation(array.opt(index));
                if (continuation != null) return continuation;
            }
        }
        return null;
    }

    /**
     * The Android client can return a section-list page whose top-level
     * reload continuation only re-runs the selected sort. The actual next
     * comments page is the nextContinuationData on the nested comment
     * itemSectionRenderer, and must take precedence over that reload token.
     */
    private static String findCommentItemSectionContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("itemSectionRenderer");
            String continuation = renderer == null ? null
                    : nextContinuation(renderer.optJSONArray("continuations"));
            if (continuation != null) return continuation;

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (isCommentLocalContinuationContainer(key)) continue;
                continuation = findCommentItemSectionContinuation(object.opt(key));
                if (continuation != null) return continuation;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                String continuation = findCommentItemSectionContinuation(array.opt(index));
                if (continuation != null) return continuation;
            }
        }
        return null;
    }

    private static String nextContinuation(JSONArray continuations) {
        if (continuations == null) return null;
        for (int index = 0; index < continuations.length(); index++) {
            JSONObject wrapper = continuations.optJSONObject(index);
            JSONObject data = wrapper == null
                    ? null : wrapper.optJSONObject("nextContinuationData");
            String token = data == null ? "" : data.optString("continuation", "");
            if (!token.isEmpty()) return token;
        }
        return null;
    }

    /**
     * Finds the page continuation while deliberately ignoring reply loaders and
     * the comments sort header. Current responses sometimes wrap the main list
     * in a section renderer, so looking for the first continuation recursively
     * can accidentally select a single comment's "view replies" token.
     */
    private static String mainContinuationFromItems(Object node) {
        if (node instanceof JSONArray) {
            JSONArray items = (JSONArray) node;
            // The main page loader is normally the final item. Searching from
            // the end also keeps nested section-list responses deterministic.
            for (int index = items.length() - 1; index >= 0; index--) {
                String continuation = mainContinuationFromItems(items.opt(index));
                if (continuation != null) return continuation;
            }
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has("commentThreadRenderer")
                    || object.has("commentRepliesRenderer")
                    || object.has("commentsHeaderRenderer")) {
                return null;
            }

            JSONObject renderer = object.optJSONObject("continuationItemRenderer");
            String continuation = endpointContinuation(renderer == null
                    ? null : renderer.optJSONObject("continuationEndpoint"));
            if (continuation != null) return continuation;

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (isCommentLocalContinuationContainer(key)) continue;
                continuation = mainContinuationFromItems(object.opt(key));
                if (continuation != null) return continuation;
            }
        }
        return null;
    }

    private static boolean isCommentLocalContinuationContainer(String key) {
        return "commentThreadRenderer".equals(key)
                || "commentRepliesRenderer".equals(key)
                || "commentsHeaderRenderer".equals(key)
                || "replies".equals(key);
    }

    private static String findSortContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("sortFilterSubMenuRenderer");
            if (renderer != null) {
                JSONArray items = renderer.optJSONArray("subMenuItems");
                if (items != null && items.length() > 0) {
                    String continuation = endpointContinuation(items.optJSONObject(0));
                    if (continuation != null) return continuation;
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String continuation = findSortContinuation(object.opt(keys.next()));
                if (continuation != null) return continuation;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                String continuation = findSortContinuation(array.opt(index));
                if (continuation != null) return continuation;
            }
        }
        return null;
    }

    private static String findAllMessagesContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("sortFilterSubMenuRenderer");
            if (renderer != null) {
                JSONArray items = renderer.optJSONArray("subMenuItems");
                if (items != null && items.length() > 0) {
                    int allMessagesIndex = items.length() > 1 ? 1 : 0;
                    String continuation = endpointContinuation(
                            items.optJSONObject(allMessagesIndex));
                    if (continuation != null) return continuation;
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String continuation = findAllMessagesContinuation(object.opt(keys.next()));
                if (continuation != null) return continuation;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                String continuation = findAllMessagesContinuation(array.opt(index));
                if (continuation != null) return continuation;
            }
        }
        return null;
    }

    private static String endpointContinuation(JSONObject object) {
        if (object == null) return null;
        JSONObject command = object.optJSONObject("continuationCommand");
        String token = command == null ? "" : command.optString("token", "");
        if (!token.isEmpty()) return token;
        JSONObject reload = object.optJSONObject("reloadContinuationData");
        token = reload == null ? "" : reload.optString("continuation", "");
        if (!token.isEmpty()) return token;
        String[] containers = {"serviceEndpoint", "continuation", "continuationEndpoint"};
        for (String container : containers) {
            token = endpointContinuation(object.optJSONObject(container));
            if (token != null) return token;
        }
        return null;
    }

    private static boolean containsCommentsDisabledMessage(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("messageRenderer");
            String message = renderer == null ? ""
                    : richTextFromRuns(renderer.optJSONObject("text")).fallbackText();
            String lower = message.toLowerCase(Locale.ROOT);
            if ((lower.contains("comment") && (lower.contains("disabled")
                    || lower.contains("turned off") || lower.contains("unavailable")))
                    || message.contains("コメントはオフ") || message.contains("コメントが無効")) {
                return true;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                if (containsCommentsDisabledMessage(object.opt(keys.next()))) return true;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                if (containsCommentsDisabledMessage(array.opt(index))) return true;
            }
        }
        return false;
    }

    private static String findCommentPanelContinuation(JSONObject root) {
        JSONArray panels = root.optJSONArray("engagementPanels");
        if (panels == null) return null;
        for (int index = 0; index < panels.length(); index++) {
            JSONObject panel = panels.optJSONObject(index);
            JSONObject renderer = panel == null
                    ? null : panel.optJSONObject("engagementPanelSectionListRenderer");
            String panelIdentifier = renderer == null
                    ? "" : renderer.optString("panelIdentifier");
            if (renderer == null || !("comment-item-section".equals(panelIdentifier)
                    || "engagement-panel-comments-section".equals(panelIdentifier))) {
                continue;
            }
            String continuation = findSectionContinuation(renderer);
            if (continuation != null) return continuation;
        }
        return null;
    }

    private static String findSectionContinuation(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject section = object.optJSONObject("sectionListRenderer");
            if (section != null) {
                String token = firstContinuation(section.optJSONArray("continuations"));
                if (token != null) return token;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String token = findSectionContinuation(object.opt(keys.next()));
                if (token != null) return token;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                String token = findSectionContinuation(array.opt(index));
                if (token != null) return token;
            }
        }
        return null;
    }

    private static void findLiveRenderer(Object node, String path, LiveRendererCandidate best) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = object.opt(key);
                if ("liveChatRenderer".equals(key) && child instanceof JSONObject) {
                    JSONObject renderer = (JSONObject) child;
                    JSONObject nested = renderer.optJSONObject("liveChatRenderer");
                    if (nested != null) renderer = nested;
                    String token = firstContinuation(renderer.optJSONArray("continuations"));
                    if (token != null) {
                        int score = path.contains("engagementPanels") ? 20 : 10;
                        if (renderer.optJSONObject("header") != null) score++;
                        if (score > best.score) {
                            best.score = score;
                            best.continuation = token;
                            best.replay = containsBoolean(renderer, "isReplay", true);
                        }
                    }
                }
                findLiveRenderer(child, path + '/' + key, best);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                findLiveRenderer(array.opt(index), path + '[' + index + ']', best);
            }
        }
    }

    private static Continuation parseLiveContinuation(JSONArray continuations) {
        if (continuations == null) return new Continuation(null, 2_000);
        String[] types = {
                "invalidationContinuationData",
                "timedContinuationData",
                "liveChatReplayContinuationData",
                "reloadContinuationData"
        };
        for (int index = 0; index < continuations.length(); index++) {
            JSONObject wrapper = continuations.optJSONObject(index);
            if (wrapper == null) continue;
            for (String type : types) {
                JSONObject data = wrapper.optJSONObject(type);
                if (data == null) continue;
                String token = data.optString("continuation", "");
                if (!token.isEmpty()) {
                    long timeout = parseLong(data, "timeoutMs", 2_000);
                    return new Continuation(token, Math.max(250, Math.min(15_000, timeout)));
                }
            }
        }
        return new Continuation(null, 2_000);
    }

    private static String firstContinuation(JSONArray continuations) {
        if (continuations == null) return null;
        for (int index = 0; index < continuations.length(); index++) {
            JSONObject wrapper = continuations.optJSONObject(index);
            if (wrapper == null) continue;
            Iterator<String> keys = wrapper.keys();
            while (keys.hasNext()) {
                JSONObject data = wrapper.optJSONObject(keys.next());
                if (data == null) continue;
                String token = data.optString("continuation", "");
                if (!token.isEmpty()) return token;
            }
        }
        return null;
    }

    private static RichCommentText richTextFromRuns(JSONObject textObject) {
        if (textObject == null) return RichCommentText.plain("");
        String simple = textObject.optString("simpleText", "");
        if (!simple.isEmpty()) return RichCommentText.plain(simple).normalized();
        JSONArray runs = textObject.optJSONArray("runs");
        if (runs == null) return RichCommentText.plain("");
        RichCommentText.Builder text = RichCommentText.builder();
        for (int index = 0; index < runs.length(); index++) {
            JSONObject run = runs.optJSONObject(index);
            if (run == null) continue;
            String value = run.optString("text", "");
            if (!value.isEmpty()) {
                text.appendText(value);
                continue;
            }
            JSONObject emoji = run.optJSONObject("emoji");
            if (emoji == null) continue;
            JSONArray shortcuts = emoji.optJSONArray("shortcuts");
            String label = shortcuts == null ? "" : shortcuts.optString(0, "");
            if (label.startsWith(":")) label = label.replace(":", "");
            String url = legacyEmojiUrl(emoji);
            if (!url.isEmpty()) {
                text.appendImage(url, label);
            } else if (!label.isEmpty()) {
                text.appendText(':' + label + ':');
            }
        }
        return text.build().normalized();
    }

    private static RichCommentText attributedText(JSONObject textObject) {
        if (textObject == null) return RichCommentText.plain("");
        String source = textObject.optString("content", "");
        JSONArray attachments = textObject.optJSONArray("attachmentRuns");
        if (attachments == null || attachments.length() == 0) {
            return RichCommentText.plain(source);
        }
        List<RichCommentText.SourceImage> images = new ArrayList<>();
        for (int index = 0; index < attachments.length(); index++) {
            JSONObject attachment = attachments.optJSONObject(index);
            if (attachment == null) continue;
            int start = (int) parseLong(attachment, "startIndex", -1);
            int length = (int) parseLong(attachment, "length", -1);
            JSONObject element = attachment.optJSONObject("element");
            String url = modernImageUrl(element);
            String label = modernImageLabel(element);
            images.add(new RichCommentText.SourceImage(start, length, url, label));
        }
        return RichCommentText.withAttachments(source, images);
    }

    private static String legacyEmojiUrl(JSONObject emoji) {
        JSONObject image = emoji.optJSONObject("image");
        JSONArray thumbnails = image == null ? null : image.optJSONArray("thumbnails");
        String url = "";
        if (thumbnails != null) {
            for (int index = 0; index < thumbnails.length(); index++) {
                JSONObject thumbnail = thumbnails.optJSONObject(index);
                if (thumbnail != null && !thumbnail.optString("url", "").isEmpty()) {
                    url = thumbnail.optString("url", "");
                }
            }
        }
        return url;
    }

    private static String modernImageUrl(JSONObject element) {
        JSONObject type = element == null ? null : element.optJSONObject("type");
        JSONObject imageType = type == null ? null : type.optJSONObject("imageType");
        JSONObject image = imageType == null ? null : imageType.optJSONObject("image");
        JSONArray sources = image == null ? null : image.optJSONArray("sources");
        String url = "";
        int bestArea = -1;
        if (sources != null) {
            for (int index = 0; index < sources.length(); index++) {
                JSONObject source = sources.optJSONObject(index);
                if (source == null) continue;
                String candidate = source.optString("url", "");
                int area = source.optInt("width", 0) * source.optInt("height", 0);
                if (!candidate.isEmpty() && area >= bestArea) {
                    url = candidate;
                    bestArea = area;
                }
            }
        }
        return url;
    }

    private static String modernImageLabel(JSONObject element) {
        JSONObject properties = element == null ? null : element.optJSONObject("properties");
        JSONObject accessibility = properties == null
                ? null : properties.optJSONObject("accessibilityProperties");
        return accessibility == null ? "" : accessibility.optString("label", "");
    }

    private static boolean containsPremiereMarker(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                String lowerKey = key.toLowerCase(Locale.ROOT);
                if (lowerKey.contains("premiere")) {
                    if (value instanceof Boolean && (Boolean) value) return true;
                    if (value instanceof Number && ((Number) value).longValue() > 0) return true;
                    if (value instanceof JSONObject && ((JSONObject) value).length() > 0) return true;
                    if (value instanceof JSONArray && ((JSONArray) value).length() > 0) return true;
                }
                if (("style".equals(lowerKey) || "label".equals(lowerKey)
                        || "badgeStyle".equals(key)) && value instanceof String) {
                    String text = ((String) value).toLowerCase(Locale.ROOT);
                    if (text.contains("premiere") || text.contains("プレミア公開")) return true;
                }
                if (containsPremiereMarker(value)) return true;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                if (containsPremiereMarker(array.opt(index))) return true;
            }
        }
        return false;
    }

    private static boolean containsBoolean(Object node, String key, boolean expected) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has(key) && object.optBoolean(key) == expected) return true;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                if (containsBoolean(object.opt(keys.next()), key, expected)) return true;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                if (containsBoolean(array.opt(index), key, expected)) return true;
            }
        }
        return false;
    }

    private static long parseLong(JSONObject object, String key, long fallback) {
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long parseIsoEpochMs(String value) {
        if (value.isEmpty()) return -1;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return -1;
        }
    }

    static final class InitialState {
        final String commentContinuation;
        final String liveContinuation;
        final boolean replay;
        final boolean premiere;

        InitialState(String commentContinuation, String liveContinuation,
                     boolean replay, boolean premiere) {
            this.commentContinuation = commentContinuation;
            this.liveContinuation = liveContinuation;
            this.replay = replay;
            this.premiere = premiere;
        }
    }

    static final class PlayerInfo {
        final boolean liveContent;
        final boolean liveNow;
        final boolean upcoming;
        final boolean premiere;
        final long liveStartEpochMs;

        PlayerInfo(boolean liveContent, boolean liveNow, boolean upcoming,
                   boolean premiere, long liveStartEpochMs) {
            this.liveContent = liveContent;
            this.liveNow = liveNow;
            this.upcoming = upcoming;
            this.premiere = premiere;
            this.liveStartEpochMs = liveStartEpochMs;
        }

        boolean replay() {
            return liveContent && !liveNow && !upcoming;
        }
    }

    static final class RegularPage {
        final List<CommentData> comments;
        final String continuation;
        final boolean commentsDisabled;

        RegularPage(List<CommentData> comments, String continuation,
                    boolean commentsDisabled) {
            this.comments = comments;
            this.continuation = continuation;
            this.commentsDisabled = commentsDisabled;
        }
    }

    static final class LivePage {
        final List<CommentData> comments;
        final String continuation;
        final long timeoutMs;
        final long lastOffsetMs;

        LivePage(List<CommentData> comments, String continuation,
                 long timeoutMs, long lastOffsetMs) {
            this.comments = comments;
            this.continuation = continuation;
            this.timeoutMs = timeoutMs;
            this.lastOffsetMs = lastOffsetMs;
        }
    }

    static final class ReplayBootstrap {
        final String continuation;

        ReplayBootstrap(String continuation) {
            this.continuation = continuation;
        }
    }

    static final class CommentData {
        final String id;
        final String text;
        final RichCommentText richText;
        final long epochMs;
        final long videoOffsetMs;
        /** ARGB highlight for Super Chat / membership messages, or 0 for regular text. */
        final int color;

        CommentData(String id, String text, long epochMs, long videoOffsetMs) {
            this(id, RichCommentText.plain(text), epochMs, videoOffsetMs);
        }

        CommentData(String id, RichCommentText richText, long epochMs, long videoOffsetMs) {
            this(id, richText, epochMs, videoOffsetMs, 0);
        }

        CommentData(String id, RichCommentText richText, long epochMs, long videoOffsetMs,
                    int color) {
            this.id = id;
            this.richText = richText == null ? RichCommentText.plain("") : richText;
            this.text = this.richText.fallbackText();
            this.epochMs = epochMs;
            this.videoOffsetMs = videoOffsetMs;
            this.color = color;
        }
    }

    private static final class Continuation {
        final String token;
        final long timeoutMs;

        Continuation(String token, long timeoutMs) {
            this.token = token;
            this.timeoutMs = timeoutMs;
        }
    }

    private static final class LiveRendererCandidate {
        int score = -1;
        String continuation;
        boolean replay;
    }
}
