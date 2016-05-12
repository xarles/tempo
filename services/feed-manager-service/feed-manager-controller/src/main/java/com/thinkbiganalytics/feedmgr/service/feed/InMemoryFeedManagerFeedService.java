package com.thinkbiganalytics.feedmgr.service.feed;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.thinkbiganalytics.feedmgr.rest.model.*;
import com.thinkbiganalytics.feedmgr.service.FileObjectPersistence;
import com.thinkbiganalytics.feedmgr.service.category.FeedManagerCategoryService;
import com.thinkbiganalytics.feedmgr.service.template.FeedManagerTemplateService;
import com.thinkbiganalytics.nifi.rest.client.NifiRestClient;
import com.thinkbiganalytics.rest.JerseyClientException;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.*;

/**
 * Created by sr186054 on 5/1/16.
 */
public class InMemoryFeedManagerFeedService extends AbstractFeedManagerFeedService implements FeedManagerFeedService {

    @Inject
    private NifiRestClient nifiRestClient;

    @Inject
    private FeedManagerCategoryService categoryProvider;

    @Inject
    private FeedManagerTemplateService templateProvider;


    private Map<String, FeedMetadata> feeds = new HashMap<>();


    @PostConstruct
    private void postConstruct() {
        Collection<FeedMetadata> savedFeeds = FileObjectPersistence.getInstance().getFeedsFromFile();
        if (savedFeeds != null) {
            Long maxId = 0L;
            for (FeedMetadata feed : savedFeeds) {

                //update the category mappings
                String categoryId = feed.getCategory().getId();
                FeedCategory category = categoryProvider.getCategoryById(categoryId);
                feed.setCategory(category);
                category.addRelatedFeed(new FeedSummary(feed));
                //add it to the map
                feeds.put(feed.getId(), feed);
            }

            loadSavedFeedsToMetaClientStore();
        }

    }


    public Collection<FeedMetadata> getFeeds() {
        return feeds.values();
    }

    public Collection<? extends UIFeed> getFeeds(boolean verbose) {
        if (verbose) {
            return getFeeds();
        } else {
            return getFeedSummaryData();
        }

    }

    public List<FeedSummary> getFeedSummaryData() {
        List<FeedSummary> summaryList = new ArrayList<>();
        if (feeds != null && !feeds.isEmpty()) {
            for (FeedMetadata feed : feeds.values()) {
                summaryList.add(new FeedSummary(feed));
            }
        }
        return summaryList;
    }


    /**
     * Get a list of all the feeds which use a Template designated as being reusable
     *
     * @return
     */
    @Override
    public List<FeedMetadata> getReusableFeeds() {
        return Lists.newArrayList(Iterables.filter(feeds.values(), new Predicate<FeedMetadata>() {
            @Override
            public boolean apply(FeedMetadata feedMetadata) {
                return feedMetadata.isReusableFeed();
            }
        }));
    }

    public List<FeedSummary> getFeedSummaryForCategory(String categoryId) {
        List<FeedSummary> summaryList = new ArrayList<>();
        FeedCategory category = categoryProvider.getCategoryById(categoryId);
   if(category != null && category.getFeeds() != null){
       summaryList.addAll(category.getFeeds());
   }
        return summaryList;
    }


    @Override
    public FeedMetadata getFeedByName(final String feedName) {

        if (feeds != null && !feeds.isEmpty()) {
            return Iterables.tryFind(feeds.values(), new Predicate<FeedMetadata>() {
                @Override
                public boolean apply(FeedMetadata metadata) {
                    return metadata.getFeedName().equalsIgnoreCase(feedName);
                }
            }).orNull();
        }
        return feeds.get(feedName);
    }

    @Override
    public FeedMetadata getFeedById(String id) {

        if (feeds != null && !feeds.isEmpty()) {
            FeedMetadata feed = feeds.get(id);
            if (feed != null) {
                //get the latest category data
                FeedCategory category = categoryProvider.getCategoryById(feed.getCategory().getId());
                feed.setCategory(category);

                //set the template to the feed

                RegisteredTemplate registeredTemplate = templateProvider.getRegisteredTemplate(feed.getTemplateId());
                if (registeredTemplate == null) {
                    registeredTemplate = templateProvider.getRegisteredTemplateByName(feed.getTemplateName());
                }
                if (registeredTemplate != null) {
                    RegisteredTemplate copy = new RegisteredTemplate(registeredTemplate);
                    copy.getProperties().clear();
                    feed.setRegisteredTemplate(copy);
                    feed.setTemplateId(copy.getNifiTemplateId());
                }

                return feed;
            }
        }

        return null;
    }


    @Override
    public List<FeedMetadata> getFeedsWithTemplate(final String registeredTemplateId) {

        return Lists.newArrayList(Iterables.filter(feeds.values(), new Predicate<FeedMetadata>() {
            @Override
            public boolean apply(FeedMetadata feed) {
                return feed.getTemplateId().equalsIgnoreCase(registeredTemplateId);
            }
        }));
    }

    /**
     * Needed to rewire feeds to ids in the server upon server start since the Feed Metadata store is in memory now
     */
    private void loadSavedFeedsToMetaClientStore() {
        for (FeedMetadata feedMetadata : feeds.values()) {
            feedMetadata.setFeedId(null);
          //  saveToMetadataStore(feedMetadata);
        }
    }

    @Override
    protected RegisteredTemplate getRegisteredTemplateWithAllProperties(String templateId) throws JerseyClientException {
        return templateProvider.getRegisteredTemplateWithAllProperties(templateId);
    }


    @Override
    public void saveFeed(FeedMetadata feed) {
        if (feed.getId() == null || !feeds.containsKey(feed.getId())) {
            feed.setId(UUID.randomUUID().toString());
            feed.setVersion(new Long(1));
        } else {
            FeedMetadata previousFeed = feeds.get(feed.getId());
            feed.setId(previousFeed.getId());
            feed.setVersion(previousFeed.getVersion() + 1L);
        }

        //match up the related category
        String categoryId = feed.getCategory().getId();
        FeedCategory category = null;
        if (categoryId != null) {
            category = categoryProvider.getCategoryById(categoryId);
        }
        if (category == null) {
            final String categoryName = feed.getCategory().getName();
            category = categoryProvider.getCategoryByName(categoryName);
            feed.setCategory(category);
        }
        if (category != null) {
            category.addRelatedFeed(new FeedSummary(feed));
        }

        //  saveToMetadataStore(feed);
        feeds.put(feed.getId(), feed);
        FileObjectPersistence.getInstance().writeFeedsToFile(feeds.values());
    }


    public void updateFeedsWithTemplate(String oldTemplateId, String newTemplateId) {
        List<FeedMetadata> feedsToUpdate = getFeedsWithTemplate(oldTemplateId);
        if (feedsToUpdate != null && !feedsToUpdate.isEmpty()) {
            for (FeedMetadata feedMetadata : feedsToUpdate) {
                feedMetadata.setTemplateId(newTemplateId);
            }
            //save the feeds
            FileObjectPersistence.getInstance().writeFeedsToFile(feeds.values());
        }
    }

    @Override
    public FeedSummary enableFeed(String feedId) {
        FeedMetadata feedMetadata = getFeedById(feedId);
        if(feedMetadata != null){
            feedMetadata.setState("ENABLED");
            return new FeedSummary(feedMetadata);
        }
      return null;
    }

    @Override
    public FeedSummary disableFeed(String feedId) {
        FeedMetadata feedMetadata = getFeedById(feedId);
        if(feedMetadata != null){
            feedMetadata.setState("DISABLED");
            return new FeedSummary(feedMetadata);
        }
        return null;
    }
}
