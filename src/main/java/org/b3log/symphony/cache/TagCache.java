/*
 * Copyright (c) 2012-2016, b3log.org & hacpai.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.symphony.cache;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.commons.lang.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.util.CollectionUtils;
import org.b3log.symphony.model.Tag;
import org.b3log.symphony.repository.TagRepository;
import org.b3log.symphony.service.ShortLinkQueryService;
import org.b3log.symphony.util.JSONs;
import org.b3log.symphony.util.Markdowns;
import org.b3log.symphony.util.Symphonys;
import org.json.JSONObject;
import org.jsoup.Jsoup;

/**
 * Tag cache.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.3.3.0, Oct 11, 2016
 * @since 1.4.0
 */
@Named
@Singleton
public class TagCache {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(TagCache.class.getName());

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Short link query service.
     */
    @Inject
    private ShortLinkQueryService shortLinkQueryService;

    /**
     * Icon tags.
     */
    private static final List<JSONObject> ICON_TAGS = new ArrayList<>();

    /**
     * New tags.
     */
    private static final List<JSONObject> NEW_TAGS = new ArrayList<>();

    /**
     * All tags.
     */
    private static final List<JSONObject> TAGS = new ArrayList<>();

    /**
     * Gets new tags with the specified fetch size.
     *
     * @return new tags
     */
    public List<JSONObject> getNewTags() {
        if (NEW_TAGS.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<>(NEW_TAGS);
    }

    /**
     * Gets icon tags with the specified fetch size.
     *
     * @param fetchSize the specified fetch size
     * @return icon tags
     */
    public List<JSONObject> getIconTags(final int fetchSize) {
        if (ICON_TAGS.isEmpty()) {
            return Collections.emptyList();
        }

        final int end = fetchSize >= ICON_TAGS.size() ? ICON_TAGS.size() - 1 : fetchSize;

        return new ArrayList<>(ICON_TAGS.subList(0, end));
    }

    /**
     * Gets all tags.
     *
     * @return all tags
     */
    public List<JSONObject> getTags() {
        if (TAGS.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<>(TAGS);
    }

    /**
     * Loads new tags.
     */
    public void loadNewTags() {
        final Query query = new Query().addSort(Keys.OBJECT_ID, SortDirection.DESCENDING).
                setCurrentPageNum(1).setPageSize(Symphonys.getInt("newTagsCnt")).setPageCount(1);

        query.setFilter(new PropertyFilter(Tag.TAG_REFERENCE_CNT, FilterOperator.GREATER_THAN, 0));

        try {
            final JSONObject result = tagRepository.get(query);
            NEW_TAGS.clear();
            NEW_TAGS.addAll(CollectionUtils.<JSONObject>jsonArrayToList(result.optJSONArray(Keys.RESULTS)));
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets new tags failed", e);
        }
    }

    /**
     * Loads icon tags.
     */
    public void loadIconTags() {
        final Query query = new Query().setFilter(
                CompositeFilterOperator.and(
                        new PropertyFilter(Tag.TAG_ICON_PATH, FilterOperator.NOT_EQUAL, ""),
                        new PropertyFilter(Tag.TAG_STATUS, FilterOperator.EQUAL, Tag.TAG_STATUS_C_VALID)))
                .setCurrentPageNum(1).setPageSize(Integer.MAX_VALUE).setPageCount(1)
                .addSort(Tag.TAG_RANDOM_DOUBLE, SortDirection.ASCENDING);
        try {
            final JSONObject result = tagRepository.get(query);
            final List<JSONObject> tags = CollectionUtils.<JSONObject>jsonArrayToList(result.optJSONArray(Keys.RESULTS));
            final List<JSONObject> toUpdateTags = new ArrayList();
            for (final JSONObject tag : tags) {
                toUpdateTags.add(JSONs.clone(tag));
            }

            for (final JSONObject tag : tags) {
                String description = tag.optString(Tag.TAG_DESCRIPTION);
                String descriptionText = tag.optString(Tag.TAG_TITLE);
                if (StringUtils.isNotBlank(description)) {
                    description = shortLinkQueryService.linkTag(description);
                    description = Markdowns.toHTML(description);

                    tag.put(Tag.TAG_DESCRIPTION, description);
                    descriptionText = Jsoup.parse(description).text();
                }

                tag.put(Tag.TAG_T_DESCRIPTION_TEXT, descriptionText);
            }

            ICON_TAGS.clear();
            ICON_TAGS.addAll(tags);

            // Updates random double
            final Transaction transaction = tagRepository.beginTransaction();
            for (final JSONObject tag : toUpdateTags) {
                tag.put(Tag.TAG_RANDOM_DOUBLE, Math.random());

                tagRepository.update(tag.optString(Keys.OBJECT_ID), tag);
            }
            transaction.commit();
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Load icon tags failed", e);
        }
    }

    /**
     * Loads all tags.
     */
    public void loadAllTags() {
        final Query query = new Query().setFilter(
                new PropertyFilter(Tag.TAG_STATUS, FilterOperator.EQUAL, Tag.TAG_STATUS_C_VALID))
                .setCurrentPageNum(1).setPageSize(Integer.MAX_VALUE).setPageCount(1);
        try {
            final JSONObject result = tagRepository.get(query);
            final List<JSONObject> tags = CollectionUtils.<JSONObject>jsonArrayToList(result.optJSONArray(Keys.RESULTS));

            // for legacy data migration
            final Transaction transaction = tagRepository.beginTransaction();
            try {
                for (final JSONObject tag : tags) {
                    String uri = tag.optString(Tag.TAG_URI);
                    if (StringUtils.isBlank(uri)) {
                        final String tagTitle = tag.optString(Tag.TAG_TITLE);
                        tag.put(Tag.TAG_URI, URLEncoder.encode(tagTitle, "UTF-8"));
                        tag.put(Tag.TAG_CSS, "");

                        tagRepository.update(tag.optString(Keys.OBJECT_ID), tag);

                        LOGGER.info("Migrated tag [title=" + tagTitle + "]");
                    }
                }

                transaction.commit();
            } catch (final RepositoryException | UnsupportedEncodingException e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }

                LOGGER.log(Level.ERROR, "Migrates tag data failed", e);
            }

            final Iterator<JSONObject> iterator = tags.iterator();
            while (iterator.hasNext()) {
                final JSONObject tag = iterator.next();

                String title = tag.optString(Tag.TAG_TITLE);
                if (StringUtils.contains(title, " ") || StringUtils.contains(title, "　")) { // filter legacy data
                    iterator.remove();

                    continue;
                }

                if (!Tag.containsWhiteListTags(title)) {
                    if (!Tag.TAG_TITLE_PATTERN.matcher(title).matches() || title.length() > Tag.MAX_TAG_TITLE_LENGTH) {
                        iterator.remove();

                        continue;
                    }
                }

                String description = tag.optString(Tag.TAG_DESCRIPTION);
                String descriptionText = title;
                if (StringUtils.isNotBlank(description)) {
                    description = shortLinkQueryService.linkTag(description);
                    description = Markdowns.toHTML(description);

                    tag.put(Tag.TAG_DESCRIPTION, description);
                    descriptionText = Jsoup.parse(description).text();
                }

                tag.put(Tag.TAG_T_DESCRIPTION_TEXT, descriptionText);
                tag.put(Tag.TAG_T_TITLE_LOWER_CASE, tag.optString(Tag.TAG_TITLE).toLowerCase());
            }

            Collections.sort(tags, new Comparator<JSONObject>() {
                @Override
                public int compare(final JSONObject t1, final JSONObject t2) {
                    final String u1Title = t1.optString(Tag.TAG_T_TITLE_LOWER_CASE);
                    final String u2Title = t2.optString(Tag.TAG_T_TITLE_LOWER_CASE);

                    return u1Title.compareTo(u2Title);
                }
            });

            TAGS.clear();
            TAGS.addAll(tags);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Load all tags failed", e);
        }
    }
}
