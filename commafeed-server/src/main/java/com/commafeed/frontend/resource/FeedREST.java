package com.commafeed.frontend.resource;

import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.media.multipart.FormDataParam;

import com.codahale.metrics.annotation.Timed;
import com.commafeed.CommaFeedApplication;
import com.commafeed.CommaFeedConfiguration;
import com.commafeed.backend.cache.CacheService;
import com.commafeed.backend.dao.FeedCategoryDAO;
import com.commafeed.backend.dao.FeedEntryStatusDAO;
import com.commafeed.backend.dao.FeedSubscriptionDAO;
import com.commafeed.backend.favicon.Favicon;
import com.commafeed.backend.feed.FeedEntryKeyword;
import com.commafeed.backend.feed.FeedFetcher;
import com.commafeed.backend.feed.FeedFetcher.FeedFetcherResult;
import com.commafeed.backend.feed.FeedRefreshEngine;
import com.commafeed.backend.feed.FeedUtils;
import com.commafeed.backend.model.Feed;
import com.commafeed.backend.model.FeedCategory;
import com.commafeed.backend.model.FeedEntry;
import com.commafeed.backend.model.FeedEntryContent;
import com.commafeed.backend.model.FeedEntryStatus;
import com.commafeed.backend.model.FeedSubscription;
import com.commafeed.backend.model.User;
import com.commafeed.backend.model.UserSettings.ReadingMode;
import com.commafeed.backend.model.UserSettings.ReadingOrder;
import com.commafeed.backend.opml.OPMLExporter;
import com.commafeed.backend.opml.OPMLImporter;
import com.commafeed.backend.service.FeedEntryFilteringService;
import com.commafeed.backend.service.FeedEntryFilteringService.FeedEntryFilterException;
import com.commafeed.backend.service.FeedEntryService;
import com.commafeed.backend.service.FeedService;
import com.commafeed.backend.service.FeedSubscriptionService;
import com.commafeed.frontend.auth.SecurityCheck;
import com.commafeed.frontend.model.Entries;
import com.commafeed.frontend.model.Entry;
import com.commafeed.frontend.model.FeedInfo;
import com.commafeed.frontend.model.Subscription;
import com.commafeed.frontend.model.UnreadCount;
import com.commafeed.frontend.model.request.FeedInfoRequest;
import com.commafeed.frontend.model.request.FeedModificationRequest;
import com.commafeed.frontend.model.request.IDRequest;
import com.commafeed.frontend.model.request.MarkRequest;
import com.commafeed.frontend.model.request.SubscribeRequest;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.rometools.opml.feed.opml.Opml;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.SyndFeedOutput;
import com.rometools.rome.io.WireFeedOutput;

import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import jakarta.ws.rs.core.Response.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/feed")
@Slf4j
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor = @__({ @Inject }))
@Singleton
@Tag(name = "Feeds")
public class FeedREST {

	private static final FeedEntry TEST_ENTRY = initTestEntry();

	private final FeedSubscriptionDAO feedSubscriptionDAO;
	private final FeedCategoryDAO feedCategoryDAO;
	private final FeedEntryStatusDAO feedEntryStatusDAO;
	private final FeedFetcher feedFetcher;
	private final FeedService feedService;
	private final FeedEntryService feedEntryService;
	private final FeedSubscriptionService feedSubscriptionService;
	private final FeedEntryFilteringService feedEntryFilteringService;
	private final FeedRefreshEngine feedRefreshEngine;
	private final OPMLImporter opmlImporter;
	private final OPMLExporter opmlExporter;
	private final CacheService cache;
	private final CommaFeedConfiguration config;

	private static FeedEntry initTestEntry() {
		FeedEntry entry = new FeedEntry();
		entry.setUrl("https://github.com/Athou/commafeed");

		FeedEntryContent content = new FeedEntryContent();
		content.setAuthor("Athou");
		content.setTitle("Merge pull request #662 from Athou/dw8");
		content.setContent("Merge pull request #662 from Athou/dw8");
		entry.setContent(content);
		return entry;
	}

	@Path("/entries")
	@GET
	@UnitOfWork
	@Operation(
			summary = "Get feed entries",
			description = "Get a list of feed entries",
			responses = { @ApiResponse(content = @Content(schema = @Schema(implementation = Entries.class))) })
	@Timed
	public Response getFeedEntries(@Parameter(hidden = true) @SecurityCheck(apiKeyAllowed = true) User user,
			@Parameter(description = "id of the feed", required = true) @QueryParam("id") String id,
			@Parameter(
					description = "all entries or only unread ones",
					required = true) @DefaultValue("unread") @QueryParam("readType") ReadingMode readType,
			@Parameter(description = "only entries newer than this") @QueryParam("newerThan") Long newerThan,
			@Parameter(description = "offset for paging") @DefaultValue("0") @QueryParam("offset") int offset,
			@Parameter(description = "limit for paging, default 20, maximum 1000") @DefaultValue("20") @QueryParam("limit") int limit,
			@Parameter(description = "ordering") @QueryParam("order") @DefaultValue("desc") ReadingOrder order,
			@Parameter(
					description = "search for keywords in either the title or the content of the entries, separated by spaces, 3 characters minimum") @QueryParam("keywords") String keywords,
			@Parameter(description = "return only entry ids") @DefaultValue("false") @QueryParam("onlyIds") boolean onlyIds) {

		Preconditions.checkNotNull(id);
		Preconditions.checkNotNull(readType);

		keywords = StringUtils.trimToNull(keywords);
		Preconditions.checkArgument(keywords == null || StringUtils.length(keywords) >= 3);
		List<FeedEntryKeyword> entryKeywords = FeedEntryKeyword.fromQueryString(keywords);

		limit = Math.min(limit, 1000);
		limit = Math.max(0, limit);

		Entries entries = new Entries();
		entries.setOffset(offset);
		entries.setLimit(limit);

		boolean unreadOnly = readType == ReadingMode.unread;

		Instant newerThanDate = newerThan == null ? null : Instant.ofEpochMilli(newerThan);

		FeedSubscription subscription = feedSubscriptionDAO.findById(user, Long.valueOf(id));
		if (subscription != null) {
			entries.setName(subscription.getTitle());
			entries.setMessage(subscription.getFeed().getMessage());
			entries.setErrorCount(subscription.getFeed().getErrorCount());
			entries.setFeedLink(subscription.getFeed().getLink());

			List<FeedEntryStatus> list = feedEntryStatusDAO.findBySubscriptions(user, Collections.singletonList(subscription), unreadOnly,
					entryKeywords, newerThanDate, offset, limit + 1, order, true, onlyIds, null, null, null);

			for (FeedEntryStatus status : list) {
				entries.getEntries().add(Entry.build(status, config.getApplicationSettings().getImageProxyEnabled()));
			}

			boolean hasMore = entries.getEntries().size() > limit;
			if (hasMore) {
				entries.setHasMore(true);
				entries.getEntries().remove(entries.getEntries().size() - 1);
			}
		} else {
			return Response.status(Status.NOT_FOUND).entity("<message>feed not found</message>").build();
		}

		entries.setTimestamp(System.currentTimeMillis());
		entries.setIgnoredReadStatus(keywords != null);
		FeedUtils.removeUnwantedFromSearch(entries.getEntries(), entryKeywords);
		return Response.ok(entries).build();
	}

	@Path("/entriesAsFeed")
	@GET
	@UnitOfWork
	@Operation(summary = "Get feed entries as a feed", description = "Get a feed of feed entries")
	@Produces(MediaType.APPLICATION_XML)
	@Timed
	public Response getFeedEntriesAsFeed(@Parameter(hidden = true) @SecurityCheck(apiKeyAllowed = true) User user,
			@Parameter(description = "id of the feed", required = true) @QueryParam("id") String id,
			@Parameter(
					description = "all entries or only unread ones",
					required = true) @DefaultValue("all") @QueryParam("readType") ReadingMode readType,
			@Parameter(description = "only entries newer than this") @QueryParam("newerThan") Long newerThan,
			@Parameter(description = "offset for paging") @DefaultValue("0") @QueryParam("offset") int offset,
			@Parameter(description = "limit for paging, default 20, maximum 1000") @DefaultValue("20") @QueryParam("limit") int limit,
			@Parameter(description = "date ordering") @QueryParam("order") @DefaultValue("desc") ReadingOrder order,
			@Parameter(
					description = "search for keywords in either the title or the content of the entries, separated by spaces, 3 characters minimum") @QueryParam("keywords") String keywords,
			@Parameter(description = "return only entry ids") @DefaultValue("false") @QueryParam("onlyIds") boolean onlyIds) {

		Response response = getFeedEntries(user, id, readType, newerThan, offset, limit, order, keywords, onlyIds);
		if (response.getStatus() != Status.OK.getStatusCode()) {
			return response;
		}
		Entries entries = (Entries) response.getEntity();

		SyndFeed feed = new SyndFeedImpl();
		feed.setFeedType("rss_2.0");
		feed.setTitle("CommaFeed - " + entries.getName());
		feed.setDescription("CommaFeed - " + entries.getName());
		feed.setLink(config.getApplicationSettings().getPublicUrl());
		feed.setEntries(entries.getEntries().stream().map(Entry::asRss).toList());

		SyndFeedOutput output = new SyndFeedOutput();
		StringWriter writer = new StringWriter();
		try {
			output.output(feed, writer);
		} catch (Exception e) {
			writer.write("Could not get feed information");
			log.error(e.getMessage(), e);
		}
		return Response.ok(writer.toString()).build();
	}

	private FeedInfo fetchFeedInternal(String url) {
		FeedInfo info;
		url = StringUtils.trimToEmpty(url);
		url = prependHttp(url);
		try {
			FeedFetcherResult feedFetcherResult = feedFetcher.fetch(url, true, null, null, null, null);
			info = new FeedInfo();
			info.setUrl(feedFetcherResult.urlAfterRedirect());
			info.setTitle(feedFetcherResult.feed().title());

		} catch (Exception e) {
			log.debug(e.getMessage(), e);
			throw new WebApplicationException(e, Response.status(Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build());
		}
		return info;
	}

	@POST
	@Path("/fetch")
	@UnitOfWork
	@Operation(
			summary = "Fetch a feed",
			description = "Fetch a feed by its url",
			responses = { @ApiResponse(content = @Content(schema = @Schema(implementation = FeedInfo.class))) })
	@Timed
	public Response fetchFeed(@Parameter(hidden = true) @SecurityCheck User user,
			@Valid @Parameter(description = "feed url", required = true) FeedInfoRequest req) {
		Preconditions.checkNotNull(req);
		Preconditions.checkNotNull(req.getUrl());

		FeedInfo info;
		try {
			info = fetchFeedInternal(req.getUrl());
		} catch (Exception e) {
			Throwable cause = Throwables.getRootCause(e);
			return Response.status(Status.INTERNAL_SERVER_ERROR)
					.entity(cause.getClass().getName() + ": " + cause.getMessage())
					.type(MediaType.TEXT_PLAIN)
					.build();
		}
		return Response.ok(info).build();
	}

	@Path("/refreshAll")
	@GET
	@UnitOfWork
	@Operation(summary = "Queue all feeds of the user for refresh", description = "Manually add all feeds of the user to the refresh queue")
	@Timed
	public Response queueAllForRefresh(@Parameter(hidden = true) @SecurityCheck User user) {
		feedSubscriptionService.refreshAll(user);
		return Response.ok().build();
	}

	@Path("/refresh")
	@POST
	@UnitOfWork
	@Operation(summary = "Queue a feed for refresh", description = "Manually add a feed to the refresh queue")
	@Timed
	public Response queueForRefresh(@Parameter(hidden = true) @SecurityCheck User user,
			@Parameter(description = "Feed id", required = true) IDRequest req) {

		Preconditions.checkNotNull(req);
		Preconditions.checkNotNull(req.getId());

		FeedSubscription sub = feedSubscriptionDAO.findById(user, req.getId());
		if (sub != null) {
			Feed feed = sub.getFeed();
			feedRefreshEngine.refreshImmediately(feed);
			return Response.ok().build();
		}
		return Response.ok(Status.NOT_FOUND).build();
	}

	@Path("/mark")
	@POST
	@UnitOfWork
	@Operation(summary = "Mark feed entries", description = "Mark feed entries as read (unread is not supported)")
	@Timed
	public Response markFeedEntries(@Parameter(hidden = true) @SecurityCheck User user,
			@Valid @Parameter(description = "Mark request", required = true) MarkRequest req) {
		Preconditions.checkNotNull(req);
		Preconditions.checkNotNull(req.getId());

		Instant olderThan = req.getOlderThan() == null ? null : Instant.ofEpochMilli(req.getOlderThan());
		Instant insertedBefore = req.getInsertedBefore() == null ? null : Instant.ofEpochMilli(req.getInsertedBefore());
		String keywords = req.getKeywords();
		List<FeedEntryKeyword> entryKeywords = FeedEntryKeyword.fromQueryString(keywords);

		FeedSubscription subscription = feedSubscriptionDAO.findById(user, Long.valueOf(req.getId()));
		if (subscription != null) {
			feedEntryService.markSubscriptionEntries(user, Collections.singletonList(subscription), olderThan, insertedBefore,
					entryKeywords);
		}
		return Response.ok().build();
	}

	@GET
	@Path("/get/{id}")
	@UnitOfWork
	@Operation(
			summary = "get feed",
			responses = { @ApiResponse(content = @Content(schema = @Schema(implementation = Subscription.class))) })
	@Timed
	public Response getFeed(@Parameter(hidden = true) @SecurityCheck User user,
			@Parameter(description = "user id", required = true) @PathParam("id") Long id) {

		Preconditions.checkNotNull(id);
		FeedSubscription sub = feedSubscriptionDAO.findById(user, id);
		if (sub == null) {
			return Response.status(Status.NOT_FOUND).build();
		}
		UnreadCount unreadCount = feedSubscriptionService.getUnreadCount(user).get(id);
		return Response.ok(Subscription.build(sub, unreadCount)).build();
	}

	@GET
	@Path("/favicon/{id}")
	@UnitOfWork
	@Operation(summary = "Fetch a feed's icon", description = "Fetch a feed's icon")
	@Timed
	public Response getFeedFavicon(@Parameter(hidden = true) @SecurityCheck User user,
			@Parameter(description = "subscription id", required = true) @PathParam("id") Long id) {

		Preconditions.checkNotNull(id);
		FeedSubscription subscription = feedSubscriptionDAO.findById(user, id);
		if (subscription == null) {
			return Response.status(Status.NOT_FOUND).build();
		}

		Feed feed = subscription.getFeed();
		Favicon icon = feedService.fetchFavicon(feed);
		ResponseBuilder builder = Response.ok(icon.getIcon(), icon.getMediaType());

		CacheControl cacheControl = new CacheControl();
		cacheControl.setMaxAge(2592000);
		cacheControl.setPrivate(false);
		builder.cacheControl(cacheControl);

		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.MONTH, 1);
		builder.expires(calendar.getTime());
		builder.lastModified(Date.from(CommaFeedApplication.STARTUP_TIME));

		return builder.build();
	}

	@POST
	@Path("/subscribe")
	@UnitOfWork
	@Operation(
			summary = "Subscribe to a feed",
			description = "Subscribe to a feed",
			responses = { @ApiResponse(content = @Content(schema = @Schema(implementation = Long.class))) })
	@Timed
	public Response subscribe(@Parameter(hidden = true) @SecurityCheck User user,
			@Valid @Parameter(description = "subscription request", required = true) SubscribeRequest req) {
		Preconditions.checkNotNull(req);
		Preconditions.checkNotNull(req.getTitle());
		Preconditions.checkNotNull(req.getUrl());

		try {
			FeedCategory category = null;
			if (req.getCategoryId() != null && !CategoryREST.ALL.equals(req.getCategoryId())) {
				category = feedCategoryDAO.findById(Long.valueOf(req.getCategoryId()));
			}

			FeedInfo info = fetchFeedInternal(prependHttp(req.getUrl()));
			long subscriptionId = feedSubscriptionService.subscribe(user, info.getUrl(), req.getTitle(), category);
			return Response.ok(subscriptionId).build();
		} catch (Exception e) {
			log.error("Failed to subscribe to URL {}: {}", req.getUrl(), e.getMessage(), e);
			return Response.status(Status.SERVICE_UNAVAILABLE)
					.entity("Failed to subscribe to URL " + req.getUrl() + ": " + e.getMessage())
					.build();
		}
	}

	@GET
	@Path("/subscribe")
	@UnitOfWork
	@Operation(summary = "Subscribe to a feed", description = "Subscribe to a feed")
	@Timed
	public Response subscribeFromUrl(@Parameter(hidden = true) @SecurityCheck User user,
			@Parameter(description = "feed url", required = true) @QueryParam("url") String url) {
		try {
			Preconditions.checkNotNull(url);
			FeedInfo info = fetchFeedInternal(prependHttp(url));
			feedSubscriptionService.subscribe(user, info.getUrl(), info.getTitle());
		} catch (Exception e) {
			log.info("Could not subscribe to url {} : {}", url, e.getMessage());
		}
		return Response.temporaryRedirect(URI.create(config.getApplicationSettings().getPublicUrl())).build();
	}

	private String prependHttp(String url) {
		if (!url.startsWith("http")) {
			url = "http://" + url;
		}
		return url;
	}

	@POST
	@Path("/unsubscribe")
	@UnitOfWork
	@Operation(summary = "Unsubscribe from a feed", description = "Unsubscribe from a feed")
	@Timed
	public Response unsubscribe(@Parameter(hidden = true) @SecurityCheck User user, @Parameter(required = true) IDRequest req) {
		Preconditions.checkNotNull(req);
		Preconditions.checkNotNull(req.getId());

		boolean deleted = feedSubscriptionService.unsubscribe(user, req.getId());
		if (deleted) {
			return Response.ok().build();
		} else {
			return Response.status(Status.NOT_FOUND).build();
		}
	}

	@POST
	@Path("/modify")
	@UnitOfWork
	@Operation(summary = "Modify a subscription", description = "Modify a feed subscription")
	@Timed
	public Response modifyFeed(@Parameter(hidden = true) @SecurityCheck User user,
			@Valid @Parameter(description = "subscription id", required = true) FeedModificationRequest req) {
		Preconditions.checkNotNull(req);
		Preconditions.checkNotNull(req.getId());

		try {
			feedEntryFilteringService.filterMatchesEntry(req.getFilter(), TEST_ENTRY);
		} catch (FeedEntryFilterException e) {
			return Response.status(Status.BAD_REQUEST).entity(e.getCause().getMessage()).type(MediaType.TEXT_PLAIN).build();
		}

		FeedSubscription subscription = feedSubscriptionDAO.findById(user, req.getId());
		subscription.setFilter(StringUtils.lowerCase(req.getFilter()));

		if (StringUtils.isNotBlank(req.getName())) {
			subscription.setTitle(req.getName());
		}

		FeedCategory parent = null;
		if (req.getCategoryId() != null && !CategoryREST.ALL.equals(req.getCategoryId())) {
			parent = feedCategoryDAO.findById(user, Long.valueOf(req.getCategoryId()));
		}
		subscription.setCategory(parent);

		if (req.getPosition() != null) {
			List<FeedSubscription> subs = feedSubscriptionDAO.findByCategory(user, parent);
			subs.sort((o1, o2) -> ObjectUtils.compare(o1.getPosition(), o2.getPosition()));

			int existingIndex = -1;
			for (int i = 0; i < subs.size(); i++) {
				if (Objects.equals(subs.get(i).getId(), subscription.getId())) {
					existingIndex = i;
				}
			}
			if (existingIndex != -1) {
				subs.remove(existingIndex);
			}

			subs.add(Math.min(req.getPosition(), subs.size()), subscription);
			for (int i = 0; i < subs.size(); i++) {
				subs.get(i).setPosition(i);
			}
			feedSubscriptionDAO.saveOrUpdate(subs);
		} else {
			feedSubscriptionDAO.saveOrUpdate(subscription);
		}
		cache.invalidateUserRootCategory(user);
		return Response.ok().build();
	}

	@POST
	@Path("/import")
	@UnitOfWork
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Operation(summary = "OPML import", description = "Import an OPML file, posted as a FORM with the 'file' name")
	@Timed
	public Response importOpml(@Parameter(hidden = true) @SecurityCheck User user,
			@Parameter(description = "ompl file", required = true) @FormDataParam("file") InputStream input) {
		if (CommaFeedApplication.USERNAME_DEMO.equals(user.getName())) {
			return Response.status(Status.FORBIDDEN).entity("Import is disabled for the demo account").build();
		}
		try {
			String opml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			opmlImporter.importOpml(user, opml);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new WebApplicationException(Response.status(Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build());
		}
		return Response.ok().build();
	}

	@GET
	@Path("/export")
	@UnitOfWork
	@Produces(MediaType.APPLICATION_XML)
	@Operation(summary = "OPML export", description = "Export an OPML file of the user's subscriptions")
	@Timed
	public Response exportOpml(@Parameter(hidden = true) @SecurityCheck User user) {
		Opml opml = opmlExporter.export(user);
		WireFeedOutput output = new WireFeedOutput();
		String opmlString;
		try {
			opmlString = output.outputString(opml);
		} catch (Exception e) {
			return Response.status(Status.INTERNAL_SERVER_ERROR).entity(e).build();
		}
		return Response.ok(opmlString).build();
	}

}
