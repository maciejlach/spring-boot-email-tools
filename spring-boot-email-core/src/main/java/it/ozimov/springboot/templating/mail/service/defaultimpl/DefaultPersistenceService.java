/*
 * Copyright 2012-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.ozimov.springboot.templating.mail.service.defaultimpl;

import it.ozimov.springboot.templating.mail.model.EmailSchedulingData;
import it.ozimov.springboot.templating.mail.service.PersistenceService;
import it.ozimov.springboot.templating.mail.utils.ByteArrayToSerializable;
import it.ozimov.springboot.templating.mail.utils.SerializableToByteArray;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Objects.nonNull;

@Service("defaultEmailPersistenceService")
@ConditionalOnProperty(prefix = "spring.mail.persistence.redis", name = "enabled")
public class DefaultPersistenceService implements PersistenceService {

    public static final String ORDERING_KEY_PREFIX = "priority-level:";

    private final StringRedisTemplate orderingTemplate;
    private final RedisTemplate<String, EmailSchedulingData> valueTemplate;

    private static class SerializerInstanceHolder {
        public static final SerializableToByteArray<EmailSchedulingData> INSTANCE = new SerializableToByteArray<>();
    }

    private static class DeserializerInstanceHolder {
        public static final ByteArrayToSerializable<EmailSchedulingData> INSTANCE = new ByteArrayToSerializable<>();
    }

    @Autowired
    public DefaultPersistenceService(@NonNull final StringRedisTemplate orderingTemplate,
                                     @NonNull final RedisTemplate<String, EmailSchedulingData> valueTemplate) {
        this.orderingTemplate = orderingTemplate;
        this.orderingTemplate.setEnableTransactionSupport(true);

        this.valueTemplate = valueTemplate;
        RedisSerializer<String> stringSerializer = new StringRedisSerializer();
        JdkSerializationRedisSerializer jdkSerializationRedisSerializer = new JdkSerializationRedisSerializer();
        this.valueTemplate.setKeySerializer(stringSerializer);
        this.valueTemplate.setValueSerializer(jdkSerializationRedisSerializer);
        this.valueTemplate.setHashKeySerializer(stringSerializer);
        this.valueTemplate.setHashValueSerializer(stringSerializer);

//        this.valueTemplate.setKeySerializer(new StringRedisSerializer());
//        this.valueTemplate.setValueSerializer(new JdkSerializationRedisSerializer());
//        this.valueTemplate.setEnableDefaultSerializer(false);
        this.valueTemplate.afterPropertiesSet();

        this.valueTemplate.setEnableTransactionSupport(true);
    }

    @Override
    public void add(@NonNull final EmailSchedulingData emailSchedulingData) {
            addOps(emailSchedulingData);
    }

    protected void addOps(final EmailSchedulingData emailSchedulingData) {
        final String orderingKey = orderingKey(emailSchedulingData);
        final String valueKey = emailSchedulingData.getId();

        final double score = calculateScore(emailSchedulingData);

        BoundZSetOperations<String, String > orderingZSetOps = orderingTemplate.boundZSetOps(orderingKey);
        orderingZSetOps.add(valueKey, score);
        orderingZSetOps.persist();

        BoundValueOperations<String, EmailSchedulingData> valueValueOps  = valueTemplate.boundValueOps(valueKey);
        valueValueOps.set(emailSchedulingData);
        valueValueOps.persist();
    }

    @Override
    public Optional<EmailSchedulingData> get(@NonNull final String id) {
        return Optional.ofNullable(getOps(id));
    }

    protected EmailSchedulingData getOps(final String id) {
        BoundValueOperations<String, EmailSchedulingData> boundValueOps = valueTemplate.boundValueOps(id);
        EmailSchedulingData emailSchedulingData = boundValueOps.get();
        return emailSchedulingData;
    }

    @Override
    public boolean remove(@NonNull final String id) {
         return removeOps(id);
    }

    protected boolean removeOps(final String id){
        final EmailSchedulingData emailSchedulingData = getOps(id);
        if(nonNull(emailSchedulingData)){
            valueTemplate.delete(id);
            final String orderingKey = orderingKey(emailSchedulingData);
            orderingTemplate.boundZSetOps(orderingKey).remove(id);
            return true;
        }

        return false;
    }

    @Override
    public void addAll(@NonNull final Collection<EmailSchedulingData> emailSchedulingDataList) {
            addAllOps(emailSchedulingDataList);
    }

    protected void addAllOps(final Collection<EmailSchedulingData> emailSchedulingDataList) {
        for(EmailSchedulingData emailSchedulingData : emailSchedulingDataList) {
            addOps(emailSchedulingData);
        }
    }

    @Override
    public Collection<EmailSchedulingData> getNextBatch(final int priorityLevel, final int batchMaxSize) {
        final String orderingKey = orderingKey(priorityLevel);
        return getNextBatchOps(orderingKey, batchMaxSize);
    }

    protected Collection<EmailSchedulingData> getNextBatchOps(final String orderingKey, final int batchMaxSize) {
        BoundZSetOperations<String, String> boundZSetOperations = orderingTemplate.boundZSetOps(orderingKey);
        long amount = boundZSetOperations.size();
        Set<String> valueIds = boundZSetOperations.range(0, Math.min(amount, batchMaxSize));
        return valueIds.stream()
                .map(id -> getOps(id))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Override
    public Collection<EmailSchedulingData> getNextBatch(final int batchMaxSize) {
        Set<String> keys = new TreeSet<>(orderingTemplate.keys(orderingKeyPrefix()+"*"));

        Set<EmailSchedulingData> emailSchedulingDataSet = new HashSet<>();
        for(String key : keys) {
            emailSchedulingDataSet.addAll(getNextBatchOps(key, Math.min(batchMaxSize, emailSchedulingDataSet.size())));
        }
        return emailSchedulingDataSet;
    }

    @Override
    public void removeAll() {
        orderingTemplate.delete("*");
        valueTemplate.delete("*");
    }

    @Override
    public void removeAll(final int priorityLevel) {
        final String orderingKey = orderingKey(priorityLevel);

        BoundZSetOperations<String, String> boundZSetOperations = orderingTemplate.boundZSetOps(orderingKey);
        long amount = boundZSetOperations.size();

        final int offset = 2_000;

        IntStream.range(0, (int) Math.ceil(amount/offset))
                .parallel()
                .forEach(i -> {
                    long start = i * offset;
                    long end = Math.min(amount, start + offset);
                    Set<String> valueIds = boundZSetOperations.range(start, end);
                    valueTemplate.delete(valueIds);

                });

        orderingTemplate.delete(orderingKey);
    }

    @Override
    public void removeAll(@NonNull final Collection<String> ids) {
        ids.parallelStream()
                .forEach(id -> removeOps(id));
    }

    private String orderingKey(final EmailSchedulingData emailSchedulingData) {
        return orderingKey(emailSchedulingData.getAssignedPriority());
    }

    private String orderingKey(final int priorityLevel) {
        return orderingKeyPrefix() + priorityLevel;
    }

    protected String orderingKeyPrefix() {
        return ORDERING_KEY_PREFIX;
    }


    private double calculateScore(final EmailSchedulingData emailSchedulingData) {
        final long nanos = emailSchedulingData.getScheduledDateTime().getLong(ChronoField.NANO_OF_SECOND);
        final int desiredPriority = emailSchedulingData.getDesiredPriority();

        final String scoreStringValue = new StringBuilder().append(nanos).append(".").append(desiredPriority).toString();
        final double score = new BigDecimal(scoreStringValue).doubleValue();
        return score;
    }

}