package io.github.sabopura.morphe.extension.youtube.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class InnertubeResponseParserTest {
    @Test
    public void parsesModernRegularCommentBodyWithoutToolbarMetadata() throws Exception {
        String json = "{\"frameworkUpdates\":{\"entityBatchUpdate\":{\"mutations\":[" +
                "{\"payload\":{\"commentEntityPayload\":{\"properties\":{" +
                "\"commentId\":\"c1\",\"content\":{\"content\":\"actual comment\"}," +
                "\"toolbarStateKey\":\"999 likes\"}}}}," +
                "{\"payload\":{\"buttonEntityPayload\":{\"text\":\"Show translation\"}}}" +
                "]}},\"continuationContents\":{\"sectionListContinuation\":{" +
                "\"continuations\":[{\"reloadContinuationData\":{" +
                "\"continuation\":\"next-token\"}}]}}}";

        InnertubeResponseParser.RegularPage page =
                InnertubeResponseParser.parseRegularPage(json);

        assertEquals(1, page.comments.size());
        assertEquals("c1", page.comments.get(0).id);
        assertEquals("actual comment", page.comments.get(0).text);
        assertEquals("next-token", page.continuation);
    }

    @Test
    public void parsesLegacyRegularCommentRunsAndEmoji() throws Exception {
        String json = "{\"commentRenderer\":{\"commentId\":\"old\"," +
                "\"contentText\":{\"runs\":[{\"text\":\"hello \"}," +
                "{\"emoji\":{\"shortcuts\":[\":wave:\"]}}]}}}";

        InnertubeResponseParser.RegularPage page =
                InnertubeResponseParser.parseRegularPage(json);

        assertEquals(1, page.comments.size());
        assertEquals("hello :wave:", page.comments.get(0).text);
    }

    @Test
    public void findsCommentAndLiveContinuationsWithoutReadingVisibleUi() throws Exception {
        String json = "{\"engagementPanels\":[{" +
                "\"engagementPanelSectionListRenderer\":{" +
                "\"panelIdentifier\":\"comment-item-section\"," +
                "\"content\":{\"sectionListRenderer\":{\"continuations\":[{" +
                "\"reloadContinuationData\":{\"continuation\":\"comments-token\"}}]}}}}," +
                "{\"engagementPanelSectionListRenderer\":{\"content\":{" +
                "\"sectionListRenderer\":{\"contents\":[{\"itemSectionRenderer\":{" +
                "\"contents\":[{\"liveChatRenderer\":{\"isReplay\":true," +
                "\"continuations\":[{\"reloadContinuationData\":{" +
                "\"continuation\":\"live-token\"}}]}}]}}]}}}}]}";

        InnertubeResponseParser.InitialState state =
                InnertubeResponseParser.parseInitial(json);

        assertEquals("comments-token", state.commentContinuation);
        assertEquals("live-token", state.liveContinuation);
        assertTrue(state.replay);
        assertFalse(state.premiere);
    }

    @Test
    public void parsesModernLiveTextAndContinuationDelay() throws Exception {
        JSONObject textModel = new JSONObject()
                .put("messageData", new JSONObject().put("attributedTextData",
                        new JSONObject().put("contentText",
                                new JSONObject().put("content", "live body only"))));
        JSONObject element = new JSONObject()
                .put("compatibilityOptions", new JSONObject().put("liveChatId", "chat-1"))
                .put("newElement", new JSONObject().put("type", new JSONObject()
                        .put("componentType", new JSONObject().put("model", new JSONObject()
                                .put("liveChatTextMessageModel", textModel)))));
        JSONObject liveChat = new JSONObject()
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("addChatItemAction", new JSONObject()
                                .put("clientId", "client-1")
                                .put("item", new JSONObject().put("elementRenderer", element)))))
                .put("continuations", new JSONArray().put(new JSONObject()
                        .put("timedContinuationData", new JSONObject()
                                .put("timeoutMs", 1250)
                                .put("continuation", "poll-next"))));
        String json = new JSONObject().put("continuationContents", new JSONObject()
                .put("liveChatContinuation", liveChat)).toString();

        InnertubeResponseParser.LivePage page =
                InnertubeResponseParser.parseLivePage(json);

        assertEquals(1, page.comments.size());
        assertEquals("chat-1", page.comments.get(0).id);
        assertEquals("live body only", page.comments.get(0).text);
        assertEquals("poll-next", page.continuation);
        assertEquals(1250L, page.timeoutMs);
    }

    @Test
    public void parsesModernAttachmentRunsWithoutReplacingLiteralSquares() throws Exception {
        JSONObject source = new JSONObject()
                .put("url", "https://yt3.ggpht.com/custom=s48")
                .put("width", 48).put("height", 48);
        JSONObject element = new JSONObject()
                .put("type", new JSONObject().put("imageType", new JSONObject()
                        .put("image", new JSONObject().put("sources",
                                new JSONArray().put(source)))))
                .put("properties", new JSONObject().put("accessibilityProperties",
                        new JSONObject().put("label", "Kusa")));
        JSONObject content = new JSONObject()
                .put("content", "literal□ attached□")
                .put("attachmentRuns", new JSONArray().put(new JSONObject()
                        .put("startIndex", 17).put("length", 1).put("element", element)));
        JSONObject model = new JSONObject().put("messageData", new JSONObject()
                .put("attributedTextData", new JSONObject().put("contentText", content)));
        JSONObject renderer = new JSONObject()
                .put("compatibilityOptions", new JSONObject().put("liveChatId", "emoji-live"))
                .put("newElement", new JSONObject().put("type", new JSONObject()
                        .put("componentType", new JSONObject().put("model", new JSONObject()
                                .put("liveChatTextMessageModel", model)))));
        JSONObject live = new JSONObject().put("actions", new JSONArray().put(new JSONObject()
                .put("addChatItemAction", new JSONObject().put("item",
                        new JSONObject().put("elementRenderer", renderer)))));
        String json = new JSONObject().put("continuationContents", new JSONObject()
                .put("liveChatContinuation", live)).toString();

        InnertubeResponseParser.CommentData comment =
                InnertubeResponseParser.parseLivePage(json).comments.get(0);

        assertEquals("literal□ attached:Kusa:", comment.text);
        assertEquals(1, comment.richText.images.size());
        assertEquals("Kusa", comment.richText.images.get(0).label);
        assertEquals("https://yt3.ggpht.com/custom=s48",
                comment.richText.images.get(0).url);
        assertTrue(comment.richText.text.contains("literal□"));
    }

    @Test
    public void parsesLegacyEmojiImageRunsAsInlineImages() throws Exception {
        JSONObject emoji = new JSONObject()
                .put("shortcuts", new JSONArray().put(":wave:"))
                .put("image", new JSONObject().put("thumbnails", new JSONArray()
                        .put(new JSONObject().put("url", "https://yt3.ggpht.com/wave=s24"))));
        JSONObject renderer = new JSONObject().put("commentRenderer", new JSONObject()
                .put("commentId", "legacy-image")
                .put("contentText", new JSONObject().put("runs", new JSONArray()
                        .put(new JSONObject().put("text", "hello "))
                        .put(new JSONObject().put("emoji", emoji)))));

        InnertubeResponseParser.CommentData comment = InnertubeResponseParser
                .parseRegularPage(renderer.toString()).comments.get(0);

        assertEquals("hello :wave:", comment.text);
        assertEquals(1, comment.richText.images.size());
        assertEquals("wave", comment.richText.images.get(0).label);
    }

    @Test
    public void parsesReplayOffsetAndSkipsViewerGuidance() throws Exception {
        String json = "{\"continuationContents\":{\"liveChatContinuation\":{" +
                "\"actions\":[{\"addChatItemAction\":{\"item\":{" +
                "\"liveChatViewerEngagementMessageRenderer\":{" +
                "\"message\":{\"runs\":[{\"text\":\"Top messages\"}]}}}}}," +
                "{\"addChatItemAction\":{\"item\":{\"liveChatTextMessageRenderer\":{" +
                "\"id\":\"legacy-live\",\"timestampUsec\":\"1000000\"," +
                "\"videoOffsetTimeMsec\":\"42000\",\"message\":{" +
                "\"runs\":[{\"text\":\"real message\"}]}}}}}]," +
                "\"continuations\":[]}}}";

        InnertubeResponseParser.LivePage page =
                InnertubeResponseParser.parseLivePage(json);

        assertEquals(1, page.comments.size());
        assertEquals("real message", page.comments.get(0).text);
        assertEquals(42_000L, page.comments.get(0).videoOffsetMs);
        assertNotNull(page.comments.get(0).id);
    }

    @Test
    public void parsesCurrentAppendContinuationItemsShape() throws Exception {
        JSONObject continuationRenderer = new JSONObject()
                .put("continuationEndpoint", new JSONObject()
                        .put("continuationCommand", new JSONObject().put("token", "page-2")));
        JSONObject command = new JSONObject().put("continuationItems", new JSONArray()
                .put(new JSONObject().put("commentRenderer", new JSONObject()
                        .put("commentId", "legacy")
                        .put("contentText", new JSONObject().put("simpleText", "body"))))
                .put(new JSONObject().put("continuationItemRenderer", continuationRenderer)));
        String json = new JSONObject().put("onResponseReceivedEndpoints", new JSONArray()
                .put(new JSONObject().put("appendContinuationItemsAction", command))).toString();

        InnertubeResponseParser.RegularPage page =
                InnertubeResponseParser.parseRegularPage(json);

        assertEquals(1, page.comments.size());
        assertEquals("page-2", page.continuation);
    }

    @Test
    public void selectsTopCommentsSortContinuation() throws Exception {
        JSONObject top = new JSONObject().put("serviceEndpoint", new JSONObject()
                .put("continuationCommand", new JSONObject().put("token", "top-comments")));
        JSONObject newest = new JSONObject().put("serviceEndpoint", new JSONObject()
                .put("continuationCommand", new JSONObject().put("token", "newest")));
        JSONObject header = new JSONObject().put("commentsHeaderRenderer", new JSONObject()
                .put("sortMenu", new JSONObject().put("sortFilterSubMenuRenderer",
                        new JSONObject().put("subMenuItems", new JSONArray().put(top).put(newest)))));
        String json = new JSONObject().put("onResponseReceivedEndpoints", new JSONArray()
                .put(new JSONObject().put("reloadContinuationItemsCommand", new JSONObject()
                        .put("continuationItems", new JSONArray().put(header))))).toString();

        assertEquals("top-comments",
                InnertubeResponseParser.parseRegularPage(json).continuation);
    }

    @Test
    public void doesNotRestartSortAfterCommentsArrive() throws Exception {
        JSONObject top = new JSONObject().put("serviceEndpoint", new JSONObject()
                .put("continuationCommand", new JSONObject().put("token", "top-comments")));
        JSONObject header = new JSONObject().put("commentsHeaderRenderer", new JSONObject()
                .put("sortMenu", new JSONObject().put("sortFilterSubMenuRenderer",
                        new JSONObject().put("subMenuItems", new JSONArray().put(top)))));
        JSONObject next = new JSONObject().put("continuationItemRenderer", new JSONObject()
                .put("continuationEndpoint", new JSONObject().put("continuationCommand",
                        new JSONObject().put("token", "page-2"))));
        JSONObject comment = new JSONObject().put("commentRenderer", new JSONObject()
                .put("commentId", "c1")
                .put("contentText", new JSONObject().put("simpleText", "body")));
        String json = new JSONObject().put("onResponseReceivedEndpoints", new JSONArray()
                .put(new JSONObject().put("reloadContinuationItemsCommand", new JSONObject()
                        .put("continuationItems", new JSONArray()
                                .put(header).put(comment).put(next))))).toString();

        assertEquals("page-2",
                InnertubeResponseParser.parseRegularPage(json).continuation);
    }

    @Test
    public void ignoresReplyContinuationInsideNestedCommentList() throws Exception {
        JSONObject reply = new JSONObject().put("continuationItemRenderer", new JSONObject()
                .put("continuationEndpoint", new JSONObject().put("continuationCommand",
                        new JSONObject().put("token", "single-comment-replies"))));
        JSONObject thread = new JSONObject().put("commentThreadRenderer", new JSONObject()
                .put("comment", new JSONObject().put("commentRenderer", new JSONObject()
                        .put("commentId", "c1")
                        .put("contentText", new JSONObject().put("simpleText", "body"))))
                .put("replies", new JSONObject().put("commentRepliesRenderer",
                        new JSONObject().put("contents", new JSONArray().put(reply)))));
        JSONObject nextPage = new JSONObject().put("continuationItemRenderer", new JSONObject()
                .put("continuationEndpoint", new JSONObject().put("continuationCommand",
                        new JSONObject().put("token", "main-page-2"))));
        JSONObject nested = new JSONObject().put("sectionListRenderer", new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("itemSectionRenderer", new JSONObject().put("contents",
                                new JSONArray().put(thread).put(nextPage))))));
        String json = new JSONObject().put("onResponseReceivedEndpoints", new JSONArray()
                .put(new JSONObject().put("reloadContinuationItemsCommand", new JSONObject()
                        .put("continuationItems", new JSONArray().put(nested))))).toString();

        InnertubeResponseParser.RegularPage page =
                InnertubeResponseParser.parseRegularPage(json);

        assertEquals(1, page.comments.size());
        assertEquals("main-page-2", page.continuation);
    }

    @Test
    public void prefersNestedNextPageOverRepeatingTopLevelReload() throws Exception {
        JSONObject repeatingSort = new JSONObject().put("reloadContinuationData",
                new JSONObject().put("continuation", "repeat-page-1"));
        JSONObject nextPage = new JSONObject().put("nextContinuationData",
                new JSONObject().put("continuation", "real-page-2"));
        JSONObject commentSection = new JSONObject().put("itemSectionRenderer",
                new JSONObject()
                        .put("contents", new JSONArray().put(new JSONObject()
                                .put("commentRenderer", new JSONObject()
                                        .put("commentId", "c1")
                                        .put("contentText", new JSONObject()
                                                .put("simpleText", "body")))))
                        .put("continuations", new JSONArray()
                                .put(new JSONObject().put("reloadContinuationData",
                                        new JSONObject().put("continuation", "other-sort")))
                                .put(nextPage)));
        JSONObject section = new JSONObject()
                .put("contents", new JSONArray().put(commentSection))
                .put("continuations", new JSONArray().put(repeatingSort));
        String json = new JSONObject().put("continuationContents", new JSONObject()
                .put("sectionListContinuation", section)).toString();

        InnertubeResponseParser.RegularPage page =
                InnertubeResponseParser.parseRegularPage(json);

        assertEquals(1, page.comments.size());
        assertEquals("real-page-2", page.continuation);
    }

    @Test
    public void neverUsesReplyContinuationAsNextCommentPage() throws Exception {
        JSONObject reply = new JSONObject().put("continuationItemRenderer", new JSONObject()
                .put("continuationEndpoint", new JSONObject().put("continuationCommand",
                        new JSONObject().put("token", "single-comment-replies"))));
        JSONObject thread = new JSONObject().put("commentThreadRenderer", new JSONObject()
                .put("comment", new JSONObject().put("commentRenderer", new JSONObject()
                        .put("commentId", "c1")
                        .put("contentText", new JSONObject().put("simpleText", "body"))))
                .put("replies", new JSONObject().put("commentRepliesRenderer",
                        new JSONObject().put("contents", new JSONArray().put(reply)))));
        String json = new JSONObject().put("onResponseReceivedEndpoints", new JSONArray()
                .put(new JSONObject().put("appendContinuationItemsAction", new JSONObject()
                        .put("continuationItems", new JSONArray().put(thread))))).toString();

        InnertubeResponseParser.RegularPage page =
                InnertubeResponseParser.parseRegularPage(json);

        assertEquals(1, page.comments.size());
        assertEquals(null, page.continuation);
    }

    @Test
    public void recognizesSecondCommentPanelIdentifier() throws Exception {
        String json = "{\"engagementPanels\":[{\"engagementPanelSectionListRenderer\":{" +
                "\"panelIdentifier\":\"engagement-panel-comments-section\"," +
                "\"content\":{\"sectionListRenderer\":{\"continuations\":[{" +
                "\"reloadContinuationData\":{\"continuation\":\"comments-token-2\"}}]}}}}]}";

        assertEquals("comments-token-2",
                InnertubeResponseParser.parseInitial(json).commentContinuation);
    }

    @Test
    public void unwrapsReplayActionsAndInheritsVideoOffset() throws Exception {
        JSONObject message = new JSONObject().put("addChatItemAction", new JSONObject()
                .put("item", new JSONObject().put("liveChatTextMessageRenderer",
                        new JSONObject().put("id", "replay-1")
                                .put("message", new JSONObject().put("simpleText", "at 42s")))));
        JSONObject replay = new JSONObject().put("replayChatItemAction", new JSONObject()
                .put("videoOffsetTimeMsec", "42000")
                .put("actions", new JSONArray().put(message)));
        JSONObject live = new JSONObject()
                .put("actions", new JSONArray().put(replay))
                .put("continuations", new JSONArray().put(new JSONObject()
                        .put("liveChatReplayContinuationData", new JSONObject()
                                .put("continuation", "replay-next"))));
        String json = new JSONObject().put("continuationContents", new JSONObject()
                .put("liveChatContinuation", live)).toString();

        InnertubeResponseParser.LivePage page =
                InnertubeResponseParser.parseLivePage(json);

        assertEquals(1, page.comments.size());
        assertEquals("at 42s", page.comments.get(0).text);
        assertEquals(42_000L, page.comments.get(0).videoOffsetMs);
        assertEquals(42_000L, page.lastOffsetMs);
        assertEquals("replay-next", page.continuation);
    }

    @Test
    public void choosesAllMessagesFromReplayBootstrapHtml() throws Exception {
        JSONObject top = new JSONObject().put("continuation", new JSONObject()
                .put("reloadContinuationData", new JSONObject().put("continuation", "top")));
        JSONObject all = new JSONObject().put("continuation", new JSONObject()
                .put("reloadContinuationData", new JSONObject().put("continuation", "all")));
        JSONObject initialData = new JSONObject().put("sortFilterSubMenuRenderer",
                new JSONObject().put("subMenuItems", new JSONArray().put(top).put(all)));
        String html = "<html><script>var ytInitialData = " + initialData + ";</script></html>";

        assertEquals("all",
                InnertubeResponseParser.parseReplayBootstrap(html).continuation);
    }

    @Test
    public void identifiesEndedPremiereFromPlayerData() throws Exception {
        JSONObject player = new JSONObject()
                .put("videoDetails", new JSONObject().put("isLiveContent", true))
                .put("premiereBadge", new JSONObject().put("style", "PREMIERE"))
                .put("microformat", new JSONObject().put("playerMicroformatRenderer",
                        new JSONObject().put("liveBroadcastDetails", new JSONObject()
                                .put("startTimestamp", "2026-01-01T00:00:00Z")
                                .put("endTimestamp", "2026-01-01T01:00:00Z"))));

        InnertubeResponseParser.PlayerInfo info =
                InnertubeResponseParser.parsePlayer(player.toString());

        assertTrue(info.liveContent);
        assertTrue(info.replay());
        assertTrue(info.premiere);
    }
}
