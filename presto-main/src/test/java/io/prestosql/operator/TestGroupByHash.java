/*
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
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import io.prestosql.Session;
import io.prestosql.block.BlockAssertions;
import io.prestosql.spi.Page;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.DictionaryBlock;
import io.prestosql.spi.block.DictionaryId;
import io.prestosql.spi.snapshot.SnapshotTestUtil;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.gen.JoinCompiler;
import io.prestosql.testing.TestingPagesSerdeFactory;
import io.prestosql.testing.TestingSession;
import io.prestosql.type.TypeUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.google.common.math.DoubleMath.log2;
import static io.prestosql.RowPagesBuilder.rowPagesBuilder;
import static io.prestosql.block.BlockAssertions.createLongSequenceBlock;
import static io.prestosql.block.BlockAssertions.createLongSequenceBlockWithNull;
import static io.prestosql.block.BlockAssertions.createLongsBlock;
import static io.prestosql.block.BlockAssertions.createStringSequenceBlock;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.GroupByHash.createGroupByHash;
import static io.prestosql.spi.block.DictionaryId.randomDictionaryId;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.type.TypeUtils.getHashBlock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestGroupByHash
{
    private static final int MAX_GROUP_ID = 500;
    private static final int[] CONTAINS_CHANNELS = new int[] {0};
    private static final Session TEST_SESSION = TestingSession.testSessionBuilder().build();
    private static final JoinCompiler JOIN_COMPILER = new JoinCompiler(createTestMetadataManager());

    @DataProvider
    public Object[][] dataType()
    {
        return new Object[][] {{VARCHAR}, {BIGINT}};
    }

    @Test
    public void testAddPage()
    {
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(BIGINT), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);
        for (int tries = 0; tries < 2; tries++) {
            for (int value = 0; value < MAX_GROUP_ID; value++) {
                Block block = BlockAssertions.createLongsBlock(value);
                Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(BIGINT), block);
                Page page = new Page(block, hashBlock);
                for (int addValuesTries = 0; addValuesTries < 10; addValuesTries++) {
                    groupByHash.addPage(page).process();
                    assertEquals(groupByHash.getGroupCount(), tries == 0 ? value + 1 : MAX_GROUP_ID);

                    // add the page again using get group ids and make sure the group count didn't change
                    Work<GroupByIdBlock> work = groupByHash.getGroupIds(page);
                    work.process();
                    GroupByIdBlock groupIds = work.getResult();
                    assertEquals(groupByHash.getGroupCount(), tries == 0 ? value + 1 : MAX_GROUP_ID);
                    assertEquals(groupIds.getGroupCount(), tries == 0 ? value + 1 : MAX_GROUP_ID);

                    // verify the first position
                    assertEquals(groupIds.getPositionCount(), 1);
                    long groupId = groupIds.getGroupId(0);
                    assertEquals(groupId, value);
                }
            }
        }
    }

    @Test
    public void testAddPageSnapshot()
    {
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(BIGINT), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);
        Object snapshot = null;
        boolean restored = false;
        for (int tries = 0; tries < 2; tries++) {
            for (int value = 0; value < MAX_GROUP_ID; value++) {
                Block block = BlockAssertions.createLongsBlock(value);
                Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(BIGINT), block);
                Page page = new Page(block, hashBlock);
                for (int addValuesTries = 0; addValuesTries < 10; addValuesTries++) {
                    groupByHash.addPage(page).process();
                    assertEquals(groupByHash.getGroupCount(), tries == 0 ? value + 1 : MAX_GROUP_ID);

                    // add the page again using get group ids and make sure the group count didn't change
                    Work<GroupByIdBlock> work = groupByHash.getGroupIds(page);
                    work.process();
                    GroupByIdBlock groupIds = work.getResult();
                    assertEquals(groupByHash.getGroupCount(), tries == 0 ? value + 1 : MAX_GROUP_ID);
                    assertEquals(groupIds.getGroupCount(), tries == 0 ? value + 1 : MAX_GROUP_ID);

                    // verify the first position
                    assertEquals(groupIds.getPositionCount(), 1);
                    long groupId = groupIds.getGroupId(0);
                    assertEquals(groupId, value);
                }
            }
            if (tries == 0) {
                snapshot = groupByHash.capture(null);
                assertEquals(SnapshotTestUtil.toSimpleSnapshotMapping(snapshot), createBigintExpectedMapping());
            }
            else if (tries == 1 && !restored) {
                //restore and go back to tries = 0
                groupByHash.restore(snapshot, null);
                snapshot = groupByHash.capture(null);
                assertEquals(SnapshotTestUtil.toSimpleSnapshotMapping(snapshot), createBigintExpectedMapping());
                tries--;
                restored = true;
            }
        }
    }

    private Map<String, Object> createBigintExpectedMapping()
    {
        Map<String, Object> expectedMapping = new HashMap<>();
        expectedMapping.put("hashCapacity", 1024);
        expectedMapping.put("maxFill", 768);
        expectedMapping.put("mask", 1023);
        expectedMapping.put("nullGroupId", -1);
        expectedMapping.put("nextGroupId", 500);
        expectedMapping.put("hashCollisions", 21807L);
        expectedMapping.put("expectedHashCollisions", 858.42432);
        expectedMapping.put("preallocatedMemoryInBytes", 0L);
        expectedMapping.put("currentPageSizeInBytes", 1072L);
        return expectedMapping;
    }

    @Test
    public void testNoChannelSnapshot()
    {
        GroupByHash groupByHash = new NoChannelGroupByHash();
        List<Page> pages = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .build();
        groupByHash.addPage(pages.get(0));
        Object snapshot = groupByHash.capture(null);
        assertEquals(snapshot, 1);
    }

    @Test
    public void testNullGroup()
    {
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(BIGINT), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);

        Block block = createLongsBlock((Long) null);
        Block hashBlock = getHashBlock(ImmutableList.of(BIGINT), block);
        Page page = new Page(block, hashBlock);
        groupByHash.addPage(page).process();

        // Add enough values to force a rehash
        block = createLongSequenceBlock(1, 132748);
        hashBlock = getHashBlock(ImmutableList.of(BIGINT), block);
        page = new Page(block, hashBlock);
        groupByHash.addPage(page).process();

        block = createLongsBlock(0);
        hashBlock = getHashBlock(ImmutableList.of(BIGINT), block);
        page = new Page(block, hashBlock);
        assertFalse(groupByHash.contains(0, page, CONTAINS_CHANNELS));
    }

    @Test
    public void testGetGroupIds()
    {
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(BIGINT), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);
        for (int tries = 0; tries < 2; tries++) {
            for (int value = 0; value < MAX_GROUP_ID; value++) {
                Block block = BlockAssertions.createLongsBlock(value);
                Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(BIGINT), block);
                Page page = new Page(block, hashBlock);
                for (int addValuesTries = 0; addValuesTries < 10; addValuesTries++) {
                    Work<GroupByIdBlock> work = groupByHash.getGroupIds(page);
                    work.process();
                    GroupByIdBlock groupIds = work.getResult();
                    assertEquals(groupIds.getGroupCount(), tries == 0 ? value + 1 : MAX_GROUP_ID);
                    assertEquals(groupIds.getPositionCount(), 1);
                    long groupId = groupIds.getGroupId(0);
                    assertEquals(groupId, value);
                }
            }
        }
    }

    @Test
    public void testGetGroupNeedRehash()
    {
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(BIGINT), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);
        // GroupByHash: the default maxFill=192, so we add a null into block when position is 192.
        Block block = createLongSequenceBlockWithNull(1, 200, 192);
        Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(BIGINT), block);
        Page page = new Page(block, hashBlock);
        Work<GroupByIdBlock> work = groupByHash.getGroupIds(page);
        boolean flag = work.process();
        if (!flag) {
            throw new RuntimeException("the block not be all process.");
        }
    }

    @Test
    public void testTypes()
    {
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(VARCHAR), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);
        // Additional bigint channel for hash
        assertEquals(groupByHash.getTypes(), ImmutableList.of(VARCHAR, BIGINT));
    }

    @Test
    public void testAppendTo()
    {
        Block valuesBlock = BlockAssertions.createStringSequenceBlock(0, 100);
        Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(VARCHAR), valuesBlock);
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(VARCHAR), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);

        Work<GroupByIdBlock> work = groupByHash.getGroupIds(new Page(valuesBlock, hashBlock));
        work.process();
        GroupByIdBlock groupIds = work.getResult();
        for (int i = 0; i < groupIds.getPositionCount(); i++) {
            assertEquals(groupIds.getGroupId(i), i);
        }
        assertEquals(groupByHash.getGroupCount(), 100);

        PageBuilder pageBuilder = new PageBuilder(groupByHash.getTypes());
        for (int i = 0; i < groupByHash.getGroupCount(); i++) {
            pageBuilder.declarePosition();
            groupByHash.appendValuesTo(i, pageBuilder, 0);
        }
        Page page = pageBuilder.build();
        // Ensure that all blocks have the same positionCount
        for (int i = 0; i < groupByHash.getTypes().size(); i++) {
            assertEquals(page.getBlock(i).getPositionCount(), 100);
        }
        assertEquals(page.getPositionCount(), 100);
        BlockAssertions.assertBlockEquals(VARCHAR, page.getBlock(0), valuesBlock);
        BlockAssertions.assertBlockEquals(BIGINT, page.getBlock(1), hashBlock);
    }

    @Test
    public void testAppendToSnapshot()
    {
        Block valuesBlock = BlockAssertions.createStringSequenceBlock(0, 100);
        Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(VARCHAR), valuesBlock);
        //MultiChannelGroupByHash
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(VARCHAR), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);

        Work<GroupByIdBlock> work = groupByHash.getGroupIds(new Page(valuesBlock, hashBlock));
        work.process();
        GroupByIdBlock groupIds = work.getResult();
        for (int i = 0; i < groupIds.getPositionCount(); i++) {
            assertEquals(groupIds.getGroupId(i), i);
        }
        assertEquals(groupByHash.getGroupCount(), 100);
        Object snapshot = groupByHash.capture(TestingPagesSerdeFactory.testingPagesSerde());
        assertEquals(SnapshotTestUtil.toSimpleSnapshotMapping(snapshot), multiChannelCreateExpectedMapping());

        PageBuilder pageBuilder = new PageBuilder(groupByHash.getTypes());
        for (int i = 0; i < groupByHash.getGroupCount(); i++) {
            pageBuilder.declarePosition();
            groupByHash.appendValuesTo(i, pageBuilder, 0);
        }
        Page page = pageBuilder.build();
        // Ensure that all blocks have the same positionCount
        for (int i = 0; i < groupByHash.getTypes().size(); i++) {
            assertEquals(page.getBlock(i).getPositionCount(), 100);
        }
        assertEquals(page.getPositionCount(), 100);

        //Restore to previous state
        groupByHash.restore(snapshot, TestingPagesSerdeFactory.testingPagesSerde());
        snapshot = groupByHash.capture(TestingPagesSerdeFactory.testingPagesSerde());
        assertEquals(SnapshotTestUtil.toSimpleSnapshotMapping(snapshot), multiChannelCreateExpectedMapping());

        //Redo work between capture and restore, then compare result
        pageBuilder = new PageBuilder(groupByHash.getTypes());
        for (int i = 0; i < groupByHash.getGroupCount(); i++) {
            pageBuilder.declarePosition();
            groupByHash.appendValuesTo(i, pageBuilder, 0);
        }
        page = pageBuilder.build();
        // Ensure that all blocks have the same positionCount
        for (int i = 0; i < groupByHash.getTypes().size(); i++) {
            assertEquals(page.getBlock(i).getPositionCount(), 100);
        }
        assertEquals(page.getPositionCount(), 100);
        BlockAssertions.assertBlockEquals(VARCHAR, page.getBlock(0), valuesBlock);
        BlockAssertions.assertBlockEquals(BIGINT, page.getBlock(1), hashBlock);
    }

    private Map<String, Object> multiChannelCreateExpectedMapping()
    {
        Map<String, Object> expectedMapping = new HashMap<>();

        expectedMapping.put("completedPagesMemorySize", 0L);
        expectedMapping.put("hashCapacity", 256);
        expectedMapping.put("maxFill", 192);
        expectedMapping.put("mask", 255);
        expectedMapping.put("groupAddressByHash", long[].class);
        expectedMapping.put("groupIdsByHash", int[].class);
        expectedMapping.put("rawHashByHashPosition", byte[].class);
        expectedMapping.put("nextGroupId", 100);
        expectedMapping.put("hashCollisions", 37L);
        expectedMapping.put("expectedHashCollisions", 0.0);
        expectedMapping.put("preallocatedMemoryInBytes", 0L);
        expectedMapping.put("currentPageSizeInBytes", 4732L);

        return expectedMapping;
    }

    @Test
    public void testAppendToMultipleTuplesPerGroup()
    {
        List<Long> values = new ArrayList<>();
        for (long i = 0; i < 100; i++) {
            values.add(i % 50);
        }
        Block valuesBlock = BlockAssertions.createLongsBlock(values);
        Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(BIGINT), valuesBlock);

        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(BIGINT), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);
        groupByHash.getGroupIds(new Page(valuesBlock, hashBlock)).process();
        assertEquals(groupByHash.getGroupCount(), 50);

        PageBuilder pageBuilder = new PageBuilder(groupByHash.getTypes());
        for (int i = 0; i < groupByHash.getGroupCount(); i++) {
            pageBuilder.declarePosition();
            groupByHash.appendValuesTo(i, pageBuilder, 0);
        }
        Page outputPage = pageBuilder.build();
        assertEquals(outputPage.getPositionCount(), 50);
        BlockAssertions.assertBlockEquals(BIGINT, outputPage.getBlock(0), BlockAssertions.createLongSequenceBlock(0, 50));
    }

    @Test
    public void testContains()
    {
        Block valuesBlock = BlockAssertions.createDoubleSequenceBlock(0, 10);
        Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(DOUBLE), valuesBlock);
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(DOUBLE), new int[] {0}, Optional.of(1), 100, JOIN_COMPILER);
        groupByHash.getGroupIds(new Page(valuesBlock, hashBlock)).process();

        Block testBlock = BlockAssertions.createDoublesBlock((double) 3);
        Block testHashBlock = TypeUtils.getHashBlock(ImmutableList.of(DOUBLE), testBlock);
        assertTrue(groupByHash.contains(0, new Page(testBlock, testHashBlock), CONTAINS_CHANNELS));

        testBlock = BlockAssertions.createDoublesBlock(11.0);
        testHashBlock = TypeUtils.getHashBlock(ImmutableList.of(DOUBLE), testBlock);
        assertFalse(groupByHash.contains(0, new Page(testBlock, testHashBlock), CONTAINS_CHANNELS));
    }

    @Test
    public void testContainsMultipleColumns()
    {
        Block valuesBlock = BlockAssertions.createDoubleSequenceBlock(0, 10);
        Block stringValuesBlock = BlockAssertions.createStringSequenceBlock(0, 10);
        Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(DOUBLE, VARCHAR), valuesBlock, stringValuesBlock);
        int[] hashChannels = {0, 1};
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(DOUBLE, VARCHAR), hashChannels, Optional.of(2), 100, JOIN_COMPILER);
        groupByHash.getGroupIds(new Page(valuesBlock, stringValuesBlock, hashBlock)).process();

        Block testValuesBlock = BlockAssertions.createDoublesBlock((double) 3);
        Block testStringValuesBlock = BlockAssertions.createStringsBlock("3");
        Block testHashBlock = TypeUtils.getHashBlock(ImmutableList.of(DOUBLE, VARCHAR), testValuesBlock, testStringValuesBlock);
        assertTrue(groupByHash.contains(0, new Page(testValuesBlock, testStringValuesBlock, testHashBlock), hashChannels));
    }

    @Test
    public void testForceRehash()
    {
        // Create a page with positionCount >> expected size of groupByHash
        Block valuesBlock = BlockAssertions.createStringSequenceBlock(0, 100);
        Block hashBlock = TypeUtils.getHashBlock(ImmutableList.of(VARCHAR), valuesBlock);

        // Create group by hash with extremely small size
        GroupByHash groupByHash = createGroupByHash(TEST_SESSION, ImmutableList.of(VARCHAR), new int[] {0}, Optional.of(1), 4, JOIN_COMPILER);
        groupByHash.getGroupIds(new Page(valuesBlock, hashBlock)).process();

        // Ensure that all groups are present in group by hash
        for (int i = 0; i < valuesBlock.getPositionCount(); i++) {
            assertTrue(groupByHash.contains(i, new Page(valuesBlock, hashBlock), CONTAINS_CHANNELS));
        }
    }

    @Test(dataProvider = "dataType")
    public void testUpdateMemory(Type type)
    {
        // Create a page with positionCount >> expected size of groupByHash
        int length = 1_000_000;
        Block valuesBlock;
        if (type == VARCHAR) {
            valuesBlock = createStringSequenceBlock(0, length);
        }
        else if (type == BIGINT) {
            valuesBlock = createLongSequenceBlock(0, length);
        }
        else {
            throw new IllegalArgumentException("unsupported data type");
        }
        Block hashBlock = getHashBlock(ImmutableList.of(type), valuesBlock);

        // Create group by hash with extremely small size
        AtomicInteger rehashCount = new AtomicInteger();
        GroupByHash groupByHash = createGroupByHash(
                ImmutableList.of(type),
                new int[] {0},
                Optional.of(1),
                1,
                false,
                JOIN_COMPILER,
                () -> {
                    rehashCount.incrementAndGet();
                    return true;
                });
        groupByHash.addPage(new Page(valuesBlock, hashBlock)).process();

        // assert we call update memory every time we rehash; the rehash count = log2(length / FILL_RATIO)
        assertEquals(rehashCount.get(), log2(length / 0.75, RoundingMode.FLOOR));
    }

    @Test(dataProvider = "dataType")
    public void testMemoryReservationYield(Type type)
    {
        // Create a page with positionCount >> expected size of groupByHash
        int length = 1_000_000;
        Block valuesBlock;
        if (type == VARCHAR) {
            valuesBlock = createStringSequenceBlock(0, length);
        }
        else if (type == BIGINT) {
            valuesBlock = createLongSequenceBlock(0, length);
        }
        else {
            throw new IllegalArgumentException("unsupported data type");
        }
        Block hashBlock = getHashBlock(ImmutableList.of(type), valuesBlock);
        Page page = new Page(valuesBlock, hashBlock);
        AtomicInteger currentQuota = new AtomicInteger(0);
        AtomicInteger allowedQuota = new AtomicInteger(3);
        UpdateMemory updateMemory = () -> {
            if (currentQuota.get() < allowedQuota.get()) {
                currentQuota.getAndIncrement();
                return true;
            }
            return false;
        };
        int yields = 0;

        // test addPage
        GroupByHash groupByHash = createGroupByHash(ImmutableList.of(type), new int[] {0}, Optional.of(1), 1, false, JOIN_COMPILER, updateMemory);
        boolean finish = false;
        Work<?> addPageWork = groupByHash.addPage(page);
        while (!finish) {
            finish = addPageWork.process();
            if (!finish) {
                assertEquals(currentQuota.get(), allowedQuota.get());
                // assert if we are blocked, we are going to be blocked again without changing allowedQuota
                assertFalse(addPageWork.process());
                assertEquals(currentQuota.get(), allowedQuota.get());
                yields++;
                allowedQuota.getAndAdd(3);
            }
        }

        // assert there is not anything missing
        assertEquals(length, groupByHash.getGroupCount());
        // assert we yield for every 3 rehashes
        // currentQuota is essentially the count we have successfully rehashed
        // the rehash count is 20 = log(1_000_000 / 0.75)
        assertEquals(currentQuota.get(), 20);
        assertEquals(currentQuota.get() / 3, yields);

        // test getGroupIds
        currentQuota.set(0);
        allowedQuota.set(3);
        yields = 0;
        groupByHash = createGroupByHash(ImmutableList.of(type), new int[] {0}, Optional.of(1), 1, false, JOIN_COMPILER, updateMemory);

        finish = false;
        Work<GroupByIdBlock> getGroupIdsWork = groupByHash.getGroupIds(page);
        while (!finish) {
            finish = getGroupIdsWork.process();
            if (!finish) {
                assertEquals(currentQuota.get(), allowedQuota.get());
                // assert if we are blocked, we are going to be blocked again without changing allowedQuota
                assertFalse(getGroupIdsWork.process());
                assertEquals(currentQuota.get(), allowedQuota.get());
                yields++;
                allowedQuota.getAndAdd(3);
            }
        }
        // assert there is not anything missing
        assertEquals(length, groupByHash.getGroupCount());
        assertEquals(length, getGroupIdsWork.getResult().getPositionCount());
        // assert we yield for every 3 rehashes
        // currentQuota is essentially the count we have successfully rehashed
        // the rehash count is 20 = log2(1_000_000 / 0.75)
        assertEquals(currentQuota.get(), 20);
        assertEquals(currentQuota.get() / 3, yields);
    }

    @Test
    public void testMemoryReservationYieldWithDictionary()
    {
        // Create a page with positionCount >> expected size of groupByHash
        int dictionaryLength = 1_000;
        int length = 2_000_000;
        int[] ids = IntStream.range(0, dictionaryLength).toArray();
        DictionaryId dictionaryId = randomDictionaryId();
        Block valuesBlock = new DictionaryBlock(dictionaryLength, createStringSequenceBlock(0, length), ids, dictionaryId);
        Block hashBlock = new DictionaryBlock(dictionaryLength, getHashBlock(ImmutableList.of(VARCHAR), valuesBlock), ids, dictionaryId);
        Page page = new Page(valuesBlock, hashBlock);
        AtomicInteger currentQuota = new AtomicInteger(0);
        AtomicInteger allowedQuota = new AtomicInteger(3);
        UpdateMemory updateMemory = () -> {
            if (currentQuota.get() < allowedQuota.get()) {
                currentQuota.getAndIncrement();
                return true;
            }
            return false;
        };
        int yields = 0;

        // test addPage
        GroupByHash groupByHash = createGroupByHash(ImmutableList.of(VARCHAR), new int[] {0}, Optional.of(1), 1, true, JOIN_COMPILER, updateMemory);

        boolean finish = false;
        Work<?> addPageWork = groupByHash.addPage(page);
        while (!finish) {
            finish = addPageWork.process();
            if (!finish) {
                assertEquals(currentQuota.get(), allowedQuota.get());
                // assert if we are blocked, we are going to be blocked again without changing allowedQuota
                assertFalse(addPageWork.process());
                assertEquals(currentQuota.get(), allowedQuota.get());
                yields++;
                allowedQuota.getAndAdd(3);
            }
        }

        // assert there is not anything missing
        assertEquals(dictionaryLength, groupByHash.getGroupCount());
        // assert we yield for every 3 rehashes
        // currentQuota is essentially the count we have successfully rehashed
        // the rehash count is 10 = log(1_000 / 0.75)
        assertEquals(currentQuota.get(), 10);
        assertEquals(currentQuota.get() / 3, yields);

        // test getGroupIds
        currentQuota.set(0);
        allowedQuota.set(3);
        yields = 0;
        groupByHash = createGroupByHash(ImmutableList.of(VARCHAR), new int[] {0}, Optional.of(1), 1, true, JOIN_COMPILER, updateMemory);

        finish = false;
        Work<GroupByIdBlock> getGroupIdsWork = groupByHash.getGroupIds(page);
        while (!finish) {
            finish = getGroupIdsWork.process();
            if (!finish) {
                assertEquals(currentQuota.get(), allowedQuota.get());
                // assert if we are blocked, we are going to be blocked again without changing allowedQuota
                assertFalse(getGroupIdsWork.process());
                assertEquals(currentQuota.get(), allowedQuota.get());
                yields++;
                allowedQuota.getAndAdd(3);
            }
        }

        // assert there is not anything missing
        assertEquals(dictionaryLength, groupByHash.getGroupCount());
        assertEquals(dictionaryLength, getGroupIdsWork.getResult().getPositionCount());
        // assert we yield for every 3 rehashes
        // currentQuota is essentially the count we have successfully rehashed
        // the rehash count is 10 = log2(1_000 / 0.75)
        assertEquals(currentQuota.get(), 10);
        assertEquals(currentQuota.get() / 3, yields);
    }
}
