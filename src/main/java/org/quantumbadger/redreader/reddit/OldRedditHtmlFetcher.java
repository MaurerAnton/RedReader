/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.reddit;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.quantumbadger.redreader.common.Constants;
import org.quantumbadger.redreader.common.UriString;
import org.quantumbadger.redreader.common.time.TimestampUTC;
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType;
import org.quantumbadger.redreader.reddit.kthings.RedditPost;
import org.quantumbadger.redreader.reddit.kthings.RedditTimestampUTC;
import org.quantumbadger.redreader.reddit.kthings.UrlEncodedString;
import org.quantumbadger.redreader.reddit.things.RedditSubreddit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches and parses old.reddit.com HTML pages as a fallback when the Reddit JSON
 * API returns 403/429 (rate limiting or blocked). Uses simple string-matching
 * instead of a full HTML parser to keep dependencies minimal.
 */
public final class OldRedditHtmlFetcher {

	private static final String TAG = "OldRedditHtmlFetcher";

	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final int READ_TIMEOUT_MS = 15_000;

	private static final String MARKER_THING_DIV = "<div class=\" thing ";
	private static final String MARKER_DATA_FULLNAME = "data-fullname=\"";
	private static final String MARKER_DATA_URL = "data-url=\"";
	private static final String MARKER_DATA_PERMALINK = "data-permalink=\"";
	private static final String MARKER_DATA_DOMAIN = "data-domain=\"";
	private static final String MARKER_DATA_SUBREDDIT = "data-subreddit=\"";
	private static final String MARKER_DATA_AUTHOR = "data-author=\"";
	private static final String MARKER_DATA_SCORE = "data-score=\"";
	private static final String MARKER_DATA_COMMENTS_COUNT = "data-comments-count=\"";
	private static final String MARKER_DATA_TIMESTAMP = "data-timestamp=\"";
	private static final String MARKER_DATA_IS_SELF = "data-is_self=\"";
	private static final String MARKER_DATA_OVER18 = "data-over18=\"";
	private static final String MARKER_DATA_SPOILER = "data-spoiler=\"";
	private static final String MARKER_DATA_STICKIED = "data-stickied=\"";
	private static final String MARKER_DATA_LOCKED = "data-locked=\"";
	private static final String MARKER_DATA_THUMBNAIL = "data-thumbnail=\"";
	private static final String MARKER_DATA_LINK_FLAIR = "data-link-flair-text=\"";
	private static final String MARKER_TITLE_A = "<a class=\"title";
	private static final String MARKER_SUBREDDIT_SEARCH_THING = "<div class=\"thing id-t5_";
	private static final String MARKER_SUBREDDIT_TITLE_A = "class=\"title\"";
	private static final String MARKER_SEARCH_RESULT = "<div class=\" search-result search-result-link";
	private static final String MARKER_SEARCH_TITLE_A = "class=\"search-title";
	private static final String MARKER_SEARCH_AUTHOR = "class=\"search-author\"";

	private OldRedditHtmlFetcher() {
		// Utility class
	}

	// -----------------------------------------------------------------------
	// Public API
	// -----------------------------------------------------------------------

	/**
	 * Fetches posts from a subreddit listing page.
	 *
	 * @param subreddit Subreddit name (without /r/ prefix), e.g. "android"
	 * @param sort      Sort order, e.g. "hot", "new", "top"
	 * @param limit     Maximum number of posts to return
	 * @return List of parsed RedditPost objects
	 * @throws IOException on network or parsing errors
	 */
	@NonNull
	public static List<RedditPost> fetchPosts(
			@NonNull final String subreddit,
			@NonNull final String sort,
			final int limit) throws IOException {

		final String path = "/r/" + subreddit + "/" + sort + "/";
		final String html = fetchHtml(Constants.Reddit.getFallbackUri(path));

		return parsePostListing(html, limit);
	}

	/**
	 * Searches for subreddits by name/description.
	 *
	 * @param query Search query string
	 * @return List of subreddit results (name and basic metadata)
	 * @throws IOException on network or parsing errors
	 */
	@NonNull
	public static List<RedditSubreddit> searchSubreddits(
			@NonNull final String query) throws IOException {

		final String path = "/subreddits/search?q=" + urlEncode(query);
		final String html = fetchHtml(Constants.Reddit.getFallbackUri(path));

		return parseSubredditSearch(html);
	}

	/**
	 * Searches for posts across all of Reddit.
	 *
	 * @param query Search query string
	 * @param sort  Sort order, e.g. "relevance", "new", "top", "comments"
	 * @param limit Maximum number of posts to return
	 * @return List of parsed RedditPost objects
	 * @throws IOException on network or parsing errors
	 */
	@NonNull
	public static List<RedditPost> searchPosts(
			@NonNull final String query,
			@NonNull final String sort,
			final int limit) throws IOException {

		final String path = "/search?q=" + urlEncode(query)
				+ "&sort=" + urlEncode(sort);
		final String html = fetchHtml(Constants.Reddit.getFallbackUri(path));

		return parseSearchResults(html, limit);
	}

	// -----------------------------------------------------------------------
	// HTTP fetching
	// -----------------------------------------------------------------------

	@NonNull
	private static String fetchHtml(@NonNull final UriString uri) throws IOException {

		final URL url = new URL(uri.value);

		HttpURLConnection connection = null;

		try {
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestMethod("GET");
			connection.setRequestProperty(
					"User-Agent",
					"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
							+ "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
			connection.setRequestProperty("Accept", "text/html");
			connection.setInstanceFollowRedirects(true);

			final int responseCode = connection.getResponseCode();

			if (responseCode >= 300) {
				throw new IOException(
						"HTTP " + responseCode + " for " + uri.value);
			}

			try (InputStream is = connection.getInputStream()) {
				return readStreamToString(is);
			}

		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@NonNull
	private static String readStreamToString(@NonNull final InputStream is)
			throws IOException {

		final BufferedReader reader = new BufferedReader(
				new InputStreamReader(is, "UTF-8"));
		final StringBuilder sb = new StringBuilder(128 * 1024);
		final char[] buf = new char[16 * 1024];
		int charsRead;

		while ((charsRead = reader.read(buf)) > 0) {
			sb.append(buf, 0, charsRead);
		}

		return sb.toString();
	}

	// -----------------------------------------------------------------------
	// Post listing parser (r/subreddit/sort/)
	// -----------------------------------------------------------------------

	@NonNull
	private static List<RedditPost> parsePostListing(
			@NonNull final String html,
			final int limit) {

		final List<RedditPost> result = new ArrayList<>();
		int searchPos = 0;

		while (result.size() < limit) {
			final int divStart = html.indexOf(MARKER_THING_DIV, searchPos);
			if (divStart < 0) {
				break;
			}

			// Find closing </div> by counting nesting depth
			final int divEnd = findClosingDiv(html, divStart);
			if (divEnd < 0) {
				searchPos = divStart + 1;
				continue;
			}

			final String thingBlock = html.substring(divStart, divEnd);

			final RedditPost post = parsePostFromThingDiv(thingBlock);
			if (post != null) {
				result.add(post);
			}

			searchPos = divEnd + 1;
		}

		return result;
	}

	@Nullable
	private static RedditPost parsePostFromThingDiv(
			@NonNull final String block) {

		// Extract data-* attributes from the outer <div>
		final String fullname = extractAttr(block, MARKER_DATA_FULLNAME);
		if (fullname == null) {
			return null;
		}

		final String id;
		if (fullname.startsWith("t3_")) {
			id = fullname.substring(3);
		} else {
			id = fullname;
		}

		final String url = extractAttr(block, MARKER_DATA_URL);
		final String permalink = extractAttr(block, MARKER_DATA_PERMALINK);
		final String domain = extractAttr(block, MARKER_DATA_DOMAIN);
		final String subreddit = extractAttr(block, MARKER_DATA_SUBREDDIT);
		final String author = extractAttr(block, MARKER_DATA_AUTHOR);
		final String scoreStr = extractAttr(block, MARKER_DATA_SCORE);
		final String commentsStr = extractAttr(
				block, MARKER_DATA_COMMENTS_COUNT);
		final String timestampStr = extractAttr(
				block, MARKER_DATA_TIMESTAMP);
		final String isSelfStr = extractAttr(block, MARKER_DATA_IS_SELF);
		final String over18Str = extractAttr(block, MARKER_DATA_OVER18);
		final String spoilerStr = extractAttr(block, MARKER_DATA_SPOILER);
		final String stickiedStr = extractAttr(block, MARKER_DATA_STICKIED);
		final String lockedStr = extractAttr(block, MARKER_DATA_LOCKED);
		final String thumbnail = extractAttr(block, MARKER_DATA_THUMBNAIL);
		final String linkFlair = extractAttr(block, MARKER_DATA_LINK_FLAIR);

		// Parse title from <a class="title ..."> inside the thing
		String title = null;
		final int titleAStart = block.indexOf(MARKER_TITLE_A);
		if (titleAStart >= 0) {
			final int titleClose = block.indexOf(">", titleAStart);
			if (titleClose >= 0) {
				final int titleEnd = block.indexOf("</a>", titleClose);
				if (titleEnd >= 0) {
					title = unescapeHtml(
							block.substring(titleClose + 1, titleEnd).trim());
				}
			}
		}

		// Parse numeric fields
		final int score = safeParseInt(scoreStr, 0);
		final int numComments = safeParseInt(commentsStr, 0);
		final long timestamp = safeParseLong(timestampStr,
				System.currentTimeMillis() / 1000L);
		final boolean isSelf = "true".equals(isSelfStr);
		final boolean over18 = "true".equals(over18Str);
		final boolean spoiler = "true".equals(spoilerStr);
		final boolean stickied = "true".equals(stickiedStr);
		final boolean locked = "true".equals(lockedStr);

		// Build the post
		try {
			return new RedditPost(
					id,
					new RedditIdAndType(fullname),
					urlEncoded(url),
					null,                                   // url_overridden_by_dest
					urlEncoded(title),
					urlEncoded(author),
					urlEncoded(domain),
					new UrlEncodedString(subreddit != null
							? subreddit : ""),
					numComments,
					score,
					0,                                      // gilded
					null,                                   // crosspost_parent
					null,                                   // upvote_ratio
					false,                                  // archived
					over18,
					false,                                  // hidden
					false,                                  // saved
					isSelf,
					false,                                  // clicked
					stickied,
					false,                                  // can_mod_post
					null,                                   // edited
					null,                                   // likes
					spoiler,
					locked,
					new RedditTimestampUTC(
							TimestampUTC.fromUtcSecs(timestamp)),
					null,                                   // selftext
					null,                                   // selftext_html
					new UrlEncodedString(permalink != null
							? permalink : ""),
					urlEncoded(linkFlair),
					null,                                   // author_flair_text
					urlEncoded(thumbnail),
					null,                                   // media
					null,                                   // preview
					false,                                  // is_video
					null,                                   // distinguished
					null,                                   // suggested_sort
					null,                                   // media_metadata
					null,                                   // gallery_data
					null                                    // removed_by_category
			);
		} catch (final Exception e) {
			Log.w(TAG, "Failed to construct RedditPost for " + fullname, e);
			return null;
		}
	}

	// -----------------------------------------------------------------------
	// Subreddit search parser (/subreddits/search)
	// -----------------------------------------------------------------------

	@NonNull
	private static List<RedditSubreddit> parseSubredditSearch(
			@NonNull final String html) {

		final List<RedditSubreddit> result = new ArrayList<>();
		int searchPos = 0;

		while (searchPos < html.length()) {
			final int thingStart = html.indexOf(
					MARKER_SUBREDDIT_SEARCH_THING, searchPos);
			if (thingStart < 0) {
				break;
			}

			final int divEnd = findClosingDiv(html, thingStart);
			if (divEnd < 0) {
				searchPos = thingStart + 1;
				continue;
			}

			final String block = html.substring(thingStart, divEnd);

			// Extract subreddit title from <a class="title" href="/r/name">
			final int titleAStart = block.indexOf(MARKER_SUBREDDIT_TITLE_A);
			if (titleAStart < 0) {
				searchPos = divEnd + 1;
				continue;
			}

			// Find href
			final int hrefStart = findSubstringEnd(
					block, "href=\"", titleAStart - 200);
			final String href = (hrefStart >= 0)
					? extractQuotedValue(block, hrefStart) : null;

			// Find link text between <a ...> and </a>
			final int tagClose = block.indexOf(">", titleAStart);
			final int aClose = block.indexOf("</a>", titleAStart);
			final String displayName = (tagClose >= 0 && aClose > tagClose)
					? unescapeHtml(block.substring(
							tagClose + 1, aClose).trim())
					: null;

			final int subscribers = findSubscribersInBlock(block);

			final RedditSubreddit sub = hrefToSubreddit(
					href, displayName, subscribers);
			if (sub != null) {
				result.add(sub);
			}

			searchPos = divEnd + 1;
		}

		return result;
	}

	@Nullable
	private static RedditSubreddit hrefToSubreddit(
			@Nullable final String href,
			@Nullable final String displayName,
			final int subscribers) {

		if (href == null || displayName == null) {
			return null;
		}

		final RedditSubreddit sub = new RedditSubreddit();
		sub.url = "https://www.reddit.com" + href;
		sub.name = href.startsWith("/r/") ? href.substring(3)
				: displayName;
		sub.display_name = displayName;
		sub.title = displayName;
		sub.subscribers = subscribers > 0 ? subscribers : null;
		return sub;
	}

	// -----------------------------------------------------------------------
	// Post search parser (/search)
	// -----------------------------------------------------------------------

	@NonNull
	private static List<RedditPost> parseSearchResults(
			@NonNull final String html,
			final int limit) {

		final List<RedditPost> result = new ArrayList<>();
		int searchPos = 0;

		while (result.size() < limit && searchPos < html.length()) {
			final int resultStart = html.indexOf(
					MARKER_SEARCH_RESULT, searchPos);
			if (resultStart < 0) {
				// Also try the old-style thing divs
				break;
			}

			// Search result blocks are self-closing-ish; find next instance or
			// a safe boundary
			final int nextResult = html.indexOf(
					MARKER_SEARCH_RESULT, resultStart + 1);
			final int thingDiv = html.indexOf(
					MARKER_THING_DIV, resultStart + 1);

			int blockEnd;
			if (nextResult >= 0 && (thingDiv < 0 || nextResult < thingDiv)) {
				blockEnd = nextResult;
			} else if (thingDiv >= 0) {
				blockEnd = thingDiv;
			} else {
				blockEnd = html.length();
			}

			final String block = html.substring(resultStart, blockEnd);

			final RedditPost post = parsePostFromSearchResultBlock(block);
			if (post != null) {
				result.add(post);
			}

			searchPos = blockEnd;
		}

		// Fallback: if the search results use the same thing-div format as
		// post listings (old.reddit.com does this for some searches)
		if (result.isEmpty()) {
			return parsePostListing(html, limit);
		}

		return result;
	}

	@Nullable
	private static RedditPost parsePostFromSearchResultBlock(
			@NonNull final String block) {

		// Extract title from <a class="search-title ...">
		final int titleAStart = findSubstring(
				block, MARKER_SEARCH_TITLE_A, 0);
		String title = null;
		String permalink = null;

		if (titleAStart >= 0) {
			final int hrefStart = findSubstringEnd(
					block, "href=\"", titleAStart - 200);
			if (hrefStart >= 0) {
				permalink = extractQuotedValue(block, hrefStart);
			}
			final int tagClose = block.indexOf(">", titleAStart);
			final int aEnd = block.indexOf("</a>", titleAStart);
			if (tagClose >= 0 && aEnd > tagClose) {
				title = unescapeHtml(
						block.substring(tagClose + 1, aEnd).trim());
			}
		}

		// Try to extract data from data-attributes that may be present
		final String fullname = extractAttr(block, MARKER_DATA_FULLNAME);
		if (fullname == null) {
			// Generate a synthetic ID from the permalink
			if (permalink == null) {
				return null;
			}
		}

		final String id = (fullname != null && fullname.startsWith("t3_"))
				? fullname.substring(3)
				: "search_" + Integer.toHexString(
						(title != null ? title : "").hashCode());

		final String actualName = (fullname != null)
				? fullname : "t3_" + id;

		final String author = extractAuthorFromSearchBlock(block);
		final String subreddit = extractSubredditFromSearchBlock(block);
		final String scoreStr = extractAttr(block, MARKER_DATA_SCORE);
		final String commentsStr = extractAttr(
				block, MARKER_DATA_COMMENTS_COUNT);
		final String domain = extractAttr(block, MARKER_DATA_DOMAIN);

		final int score = safeParseInt(scoreStr, 0);
		final int numComments = safeParseInt(commentsStr, 0);

		try {
			return new RedditPost(
					id,
					new RedditIdAndType(actualName),
					null,                                   // url
					null,                                   // url_overridden_by_dest
					urlEncoded(title),
					urlEncoded(author),
					urlEncoded(domain),
					new UrlEncodedString(subreddit != null
							? subreddit : ""),
					numComments,
					score,
					0,                                      // gilded
					null,                                   // crosspost_parent
					null,                                   // upvote_ratio
					false,                                  // archived
					false,                                  // over_18
					false,                                  // hidden
					false,                                  // saved
					false,                                  // is_self
					false,                                  // clicked
					false,                                  // stickied
					false,                                  // can_mod_post
					null,                                   // edited
					null,                                   // likes
					false,                                  // spoiler
					false,                                  // locked
					new RedditTimestampUTC(
							TimestampUTC.fromUtcSecs(
									System.currentTimeMillis() / 1000L)),
					null,                                   // selftext
					null,                                   // selftext_html
					new UrlEncodedString(permalink != null
							? permalink : ""),
					null,                                   // link_flair_text
					null,                                   // author_flair_text
					null,                                   // thumbnail
					null,                                   // media
					null,                                   // preview
					false,                                  // is_video
					null,                                   // distinguished
					null,                                   // suggested_sort
					null,                                   // media_metadata
					null,                                   // gallery_data
					null                                    // removed_by_category
			);
		} catch (final Exception e) {
			Log.w(TAG, "Failed to construct RedditPost from search", e);
			return null;
		}
	}

	// -----------------------------------------------------------------------
	// HTML utility helpers
	// -----------------------------------------------------------------------

	/**
	 * Finds the matching closing &lt;/div&gt; tag for a div starting at the
	 * given position. Accounts for nested div tags.
	 */
	private static int findClosingDiv(
			@NonNull final String html,
			final int divStart) {

		int pos = divStart;
		int depth = 0;

		while (pos < html.length()) {
			final int nextOpen = html.indexOf("<div", pos);
			final int nextClose = html.indexOf("</div>", pos);

			if (nextClose < 0) {
				return -1;
			}

			if (nextOpen >= 0 && nextOpen < nextClose) {
				depth++;
				pos = nextOpen + 4;
			} else {
				depth--;
				if (depth <= 0) {
					return nextClose + "</div>".length();
				}
				pos = nextClose + 6;
			}
		}

		return -1;
	}

	/**
	 * Extracts a data-attribute value from an HTML tag.
	 * Looks for attr="value" pattern.
	 */
	@Nullable
	private static String extractAttr(
			@NonNull final String html,
			@NonNull final String attrName) {

		final int attrStart = html.indexOf(attrName);
		if (attrStart < 0) {
			return null;
		}

		return extractQuotedValue(html, attrStart + attrName.length());
	}

	/**
	 * Extracts a double-quoted value starting at the position where the
	 * opening quote is expected.
	 */
	@Nullable
	private static String extractQuotedValue(
			@NonNull final String html,
			final int startAfterEquals) {

		if (startAfterEquals >= html.length()) {
			return null;
		}

		if (html.charAt(startAfterEquals) != '"') {
			return null;
		}

		final int endQuote = html.indexOf('"', startAfterEquals + 1);
		if (endQuote < 0) {
			return null;
		}

		return unescapeHtml(
				html.substring(startAfterEquals + 1, endQuote));
	}

	/**
	 * Finds the position of the end of a substring (i.e. right after it).
	 */
	private static int findSubstringEnd(
			@NonNull final String haystack,
			@NonNull final String needle,
			final int startFrom) {

		final int pos = findSubstring(haystack, needle, startFrom);
		if (pos < 0) {
			return -1;
		}
		return pos + needle.length();
	}

	/**
	 * Finds a substring position with a safe non-negative start.
	 */
	private static int findSubstring(
			@NonNull final String haystack,
			@NonNull final String needle,
			int startFrom) {

		if (startFrom < 0) {
			startFrom = 0;
		}
		return haystack.indexOf(needle, startFrom);
	}

	@Nullable
	private static String extractAuthorFromSearchBlock(
			@NonNull final String block) {

		final int authAStart = block.indexOf(MARKER_SEARCH_AUTHOR);
		if (authAStart < 0) {
			return null;
		}
		final int tagClose = block.indexOf(">", authAStart);
		final int aEnd = block.indexOf("</a>", authAStart);
		if (tagClose >= 0 && aEnd > tagClose) {
			return unescapeHtml(
					block.substring(tagClose + 1, aEnd).trim());
		}
		return null;
	}

	@Nullable
	private static String extractSubredditFromSearchBlock(
			@NonNull final String block) {

		final int markerPos = block.indexOf("class=\"search-subreddit-link\"");
		if (markerPos < 0) {
			return null;
		}
		final int tagClose = block.indexOf(">", markerPos);
		final int aEnd = block.indexOf("</a>", markerPos);
		if (tagClose >= 0 && aEnd > tagClose) {
			final String raw = block.substring(
					tagClose + 1, aEnd).trim();
			// Strip "/r/" prefix if present
			if (raw.startsWith("/r/")) {
				return raw.substring(3);
			}
			return unescapeHtml(raw);
		}
		return null;
	}

	/**
	 * Looks for a subscriber count like "123 readers" in the HTML block.
	 */
	private static int findSubscribersInBlock(
			@NonNull final String block) {

		final int readersPos = block.indexOf("readers");
		if (readersPos < 0) {
			return 0;
		}

		// Walk backwards to find the number
		int numEnd = readersPos - 1;
		while (numEnd > 0 && block.charAt(numEnd) == ' ') {
			numEnd--;
		}

		int numStart = numEnd;
		while (numStart > 0
				&& Character.isDigit(block.charAt(numStart - 1))) {
			numStart--;
		}

		if (numStart <= numEnd) {
			try {
				return Integer.parseInt(
						block.substring(numStart, numEnd + 1));
			} catch (final NumberFormatException e) {
				return 0;
			}
		}

		return 0;
	}

	// -----------------------------------------------------------------------
	// String helpers
	// -----------------------------------------------------------------------

	private static int safeParseInt(
			@Nullable final String str,
			final int defaultVal) {

		if (str == null) {
			return defaultVal;
		}
		try {
			return Integer.parseInt(str);
		} catch (final NumberFormatException e) {
			return defaultVal;
		}
	}

	private static long safeParseLong(
			@Nullable final String str,
			final long defaultVal) {

		if (str == null) {
			return defaultVal;
		}
		try {
			return Long.parseLong(str);
		} catch (final NumberFormatException e) {
			return defaultVal;
		}
	}

	@Nullable
	private static UrlEncodedString urlEncoded(
			@Nullable final String str) {

		if (str == null || str.isEmpty()) {
			return null;
		}
		return new UrlEncodedString(str);
	}

	@NonNull
	private static String urlEncode(@NonNull final String value) {

		try {
			return java.net.URLEncoder.encode(value, "UTF-8");
		} catch (final java.io.UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Basic HTML entity unescaping for common entities.
	 */
	@NonNull
	private static String unescapeHtml(@NonNull final String input) {

		final String result = input
				.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&#x27;", "'")
				.replace("&#x2F;", "/");

		// Handle numeric entities like &#123;
		final java.util.regex.Matcher matcher = NUMERIC_ENTITY_PATTERN.matcher(result);
		final StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			try {
				final int codePoint = Integer.parseInt(matcher.group(1));
				matcher.appendReplacement(sb,
						String.valueOf((char) codePoint));
			} catch (final Exception e) {
				matcher.appendReplacement(sb, matcher.group(0));
			}
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	private static final java.util.regex.Pattern NUMERIC_ENTITY_PATTERN =
			java.util.regex.Pattern.compile("&#(\\d+);");
}
