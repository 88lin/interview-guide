import assert from 'node:assert/strict';
import test from 'node:test';

import { calculateInterviewStats } from './interviewHistoryStats.ts';

const allItems = [
  { status: 'IN_PROGRESS', evaluateStatus: null, overallScore: null },
  { status: 'COMPLETED', evaluateStatus: 'PROCESSING', overallScore: null },
  { status: 'EVALUATED', evaluateStatus: 'COMPLETED', overallScore: 80 },
];

test('普通面试页保持按全部记录统计，不受当前筛选结果影响', () => {
  const stats = calculateInterviewStats(allItems, [allItems[2]], false);

  assert.deepEqual(stats, {
    totalCount: 3,
    completedCount: 1,
    averageScore: 80,
  });
});

test('知识库面试页按筛选结果统计，并将已提交和已评估都计为完成', () => {
  const filteredItems = [allItems[1], allItems[2]];

  const stats = calculateInterviewStats(allItems, filteredItems, true);

  assert.deepEqual(stats, {
    totalCount: 2,
    completedCount: 2,
    averageScore: 80,
  });
});
