package com.commafeed.frontend.servlet;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.commafeed.CommaFeedConfiguration;
import com.commafeed.backend.dao.FeedCategoryDAO;
import com.commafeed.backend.dao.FeedEntryStatusDAO;
import com.commafeed.backend.dao.FeedSubscriptionDAO;
import com.commafeed.backend.dao.UnitOfWork;
import com.commafeed.backend.dao.UserDAO;
import com.commafeed.backend.model.FeedCategory;
import com.commafeed.backend.model.FeedEntryStatus;
import com.commafeed.backend.model.FeedSubscription;
import com.commafeed.backend.model.User;
import com.commafeed.backend.model.UserSettings.ReadingOrder;
import com.commafeed.backend.service.FeedEntryService;
import com.commafeed.backend.service.UserService;
import com.commafeed.frontend.resource.CategoryREST;
import com.commafeed.frontend.session.SessionHelper;
import com.google.common.collect.Iterables;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@SuppressWarnings("serial")
@RequiredArgsConstructor(onConstructor = @__({ @Inject }))
@Singleton
public class NextUnreadServlet extends HttpServlet {

	private static final String PARAM_CATEGORYID = "category";
	private static final String PARAM_READINGORDER = "order";

	private final UnitOfWork unitOfWork;
	private final FeedSubscriptionDAO feedSubscriptionDAO;
	private final FeedEntryStatusDAO feedEntryStatusDAO;
	private final FeedCategoryDAO feedCategoryDAO;
	private final UserDAO userDAO;
	private final UserService userService;
	private final FeedEntryService feedEntryService;
	private final CommaFeedConfiguration config;

	@Override
	protected void doGet(final HttpServletRequest req, HttpServletResponse resp) throws IOException {
		final String categoryId = req.getParameter(PARAM_CATEGORYID);
		String orderParam = req.getParameter(PARAM_READINGORDER);

		SessionHelper sessionHelper = new SessionHelper(req);
		Optional<Long> userId = sessionHelper.getLoggedInUserId();
		Optional<User> user = unitOfWork.call(() -> userId.map(userDAO::findById));
		user.ifPresent(value -> unitOfWork.run(() -> userService.performPostLoginActivities(value)));
		if (user.isEmpty()) {
			resp.sendRedirect(resp.encodeRedirectURL(config.getApplicationSettings().getPublicUrl()));
			return;
		}

		final ReadingOrder order = StringUtils.equals(orderParam, "asc") ? ReadingOrder.asc : ReadingOrder.desc;

		FeedEntryStatus status = unitOfWork.call(() -> {
			FeedEntryStatus s = null;
			if (StringUtils.isBlank(categoryId) || CategoryREST.ALL.equals(categoryId)) {
				List<FeedSubscription> subs = feedSubscriptionDAO.findAll(user.get());
				List<FeedEntryStatus> statuses = feedEntryStatusDAO.findBySubscriptions(user.get(), subs, true, null, null, 0, 1, order,
						true, null, null, null);
				s = Iterables.getFirst(statuses, null);
			} else {
				FeedCategory category = feedCategoryDAO.findById(user.get(), Long.valueOf(categoryId));
				if (category != null) {
					List<FeedCategory> children = feedCategoryDAO.findAllChildrenCategories(user.get(), category);
					List<FeedSubscription> subscriptions = feedSubscriptionDAO.findByCategories(user.get(), children);
					List<FeedEntryStatus> statuses = feedEntryStatusDAO.findBySubscriptions(user.get(), subscriptions, true, null, null, 0,
							1, order, true, null, null, null);
					s = Iterables.getFirst(statuses, null);
				}
			}
			if (s != null) {
				feedEntryService.markEntry(user.get(), s.getEntry().getId(), true);
			}
			return s;
		});

		if (status == null) {
			resp.sendRedirect(resp.encodeRedirectURL(config.getApplicationSettings().getPublicUrl()));
		} else {
			String url = status.getEntry().getUrl();
			resp.sendRedirect(resp.encodeRedirectURL(url));
		}
	}
}
