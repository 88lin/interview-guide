import assert from 'node:assert/strict';
import test from 'node:test';
import {
  getFollowUpQualityWarning,
  getSelectedCapacity,
  getStrictCapacityMessage,
} from './interviewCapacity.ts';

const options = [
  { followUpCount: 0, availableQuestionCount: 10, selectable: true },
  { followUpCount: 1, availableQuestionCount: 8, selectable: true },
  { followUpCount: 2, availableQuestionCount: 5, selectable: true },
  { followUpCount: 3, availableQuestionCount: 2, selectable: false },
];

test('根据严格追问数量找到后端容量选项', () => {
  assert.deepEqual(getSelectedCapacity(options, 3), options[3]);
  assert.equal(getSelectedCapacity(options, 4), null);
});

test('容量不足提示保留用户选择并说明当前最大严格追问数', () => {
  assert.equal(
    getStrictCapacityMessage(options, 3, 5),
    '当前仅有 2 道题包含至少 3 个追问，无法抽取 5 道主问题。在当前题量下，每题最多可严格保证 2 个追问。'
  );
});

test('最近生成目标只对追问不足的题目显示质量警告', () => {
  assert.equal(getFollowUpQualityWarning(1, 2), '追问不足：实际 1 / 目标 2');
  assert.equal(getFollowUpQualityWarning(2, 2), null);
  assert.equal(getFollowUpQualityWarning(3, 2), null);
  assert.equal(getFollowUpQualityWarning(0, null), null);
});
