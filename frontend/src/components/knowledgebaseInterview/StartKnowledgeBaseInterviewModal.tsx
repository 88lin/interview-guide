import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { AlertCircle, Loader2, Play, X } from 'lucide-react';
import type { KnowledgeBaseItem, KnowledgeBaseQuestion } from '../../api/knowledgebase';
import { knowledgeBaseApi } from '../../api/knowledgebase';
import {
  DEFAULT_DIFFICULTY,
  DIFFICULTY_OPTIONS,
  FOLLOW_UP_COUNT_OPTIONS,
  INPUT_CLASS,
  MAIN_QUESTION_COUNT_OPTIONS,
} from '../../constants/knowledgebaseInterview';

export interface StartInterviewConfig {
  category: string;  // 空字符串表示覆盖全部方向
  difficulty: string;
  mainQuestionCount: number;
  followUpCount: number;
}

interface StartKnowledgeBaseInterviewModalProps {
  open: boolean;
  knowledgeBase: KnowledgeBaseItem | null;
  defaultDifficulty?: string;
  starting: boolean;
  error: string;
  onClose: () => void;
  onStart: (config: StartInterviewConfig) => void;
}

export default function StartKnowledgeBaseInterviewModal({
  open,
  knowledgeBase,
  defaultDifficulty = DEFAULT_DIFFICULTY,
  starting,
  error,
  onClose,
  onStart,
}: StartKnowledgeBaseInterviewModalProps) {
  const [category, setCategory] = useState('');
  const [difficulty, setDifficulty] = useState(defaultDifficulty);
  const [mainQuestionCount, setMainQuestionCount] = useState(5);
  const [followUpCount, setFollowUpCount] = useState(1);
  // 保留原始已启用题目列表，用于本地按 category/difficulty/followUps 计算真实可用数，
  // 不能只存聚合后的方向计数——那样会丢失难度和追问数过滤
  const [activeQuestions, setActiveQuestions] = useState<KnowledgeBaseQuestion[]>([]);
  const [categories, setCategories] = useState<Array<{ category: string; count: number }>>([]);
  const [loadingCategories, setLoadingCategories] = useState(false);
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    if (!open || !knowledgeBase) {
      setActiveQuestions([]);
      setCategories([]);
      setLoadError('');
      return;
    }
    setCategory('');
    setDifficulty(defaultDifficulty);
    setMainQuestionCount(5);
    setFollowUpCount(1);
    let cancelled = false;
    setLoadingCategories(true);
    setLoadError('');
    // 只取该知识库的已启用题目；方向列表与可用题数都基于这份列表本地聚合，
    // 避免每次 category/difficulty/followUpCount 变化都额外请求后端
    knowledgeBaseApi
      .listQuestions(knowledgeBase.id, { status: 'ACTIVE' })
      .then(list => {
        if (cancelled) return;
        setActiveQuestions(list);
        // 直接基于已启用题目本地聚合方向，避免额外调用 distinct 接口
        const counter = new Map<string, number>();
        list.forEach(q => {
          if (q.category) {
            counter.set(q.category, (counter.get(q.category) || 0) + 1);
          }
        });
        setCategories(
          Array.from(counter.entries())
            .map(([cat, count]) => ({ category: cat, count }))
            .sort((a, b) => b.count - a.count || a.category.localeCompare(b.category))
        );
      })
      .catch(err => {
        if (!cancelled) {
          setLoadError(err instanceof Error ? err.message : '加载方向失败');
        }
      })
      .finally(() => {
        if (!cancelled) setLoadingCategories(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, knowledgeBase, defaultDifficulty]);

  // 可用主问题数：与后端实际筛选条件对齐——
  //   category 为空时跨全部方向；category/difficulty 必须匹配；
  //   followUpCount > 0 时至少要求有 1 个追问，否则该题不能入选
  const availableCount = useMemo(() => {
    return activeQuestions.filter(q => {
      if (category && q.category !== category) return false;
      if (q.difficulty !== difficulty) return false;
      if (followUpCount > 0 && q.followUps.length === 0) return false;
      return true;
    }).length;
  }, [activeQuestions, category, difficulty, followUpCount]);

  const canStart = availableCount >= mainQuestionCount && mainQuestionCount > 0;

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
          />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={e => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-lg w-full"
            >
              <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100 dark:border-slate-700">
                <div className="flex items-center gap-2">
                  <Play className="w-5 h-5 text-primary-500" />
                  <div>
                    <h3 className="text-lg font-bold text-slate-900 dark:text-white">开始知识库面试</h3>
                    <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                      仅从 <span className="font-medium">{knowledgeBase?.name}</span> 的已启用题目抽题
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={onClose}
                  disabled={starting}
                  className="p-2 rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              <div className="px-6 py-5 space-y-4">
                <label className="block">
                  <span className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">面试方向</span>
                  <select
                    value={category}
                    onChange={event => setCategory(event.target.value)}
                    className={INPUT_CLASS}
                    disabled={loadingCategories}
                  >
                    <option value="">全部方向</option>
                    {categories.map(item => (
                      <option key={item.category} value={item.category}>
                        {item.category}（{item.count} 题）
                      </option>
                    ))}
                  </select>
                </label>

                <div className="grid grid-cols-3 gap-3">
                  <label className="block">
                    <span className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">难度</span>
                    <select
                      value={difficulty}
                      onChange={event => setDifficulty(event.target.value)}
                      className={INPUT_CLASS}
                    >
                      {DIFFICULTY_OPTIONS.map(option => (
                        <option key={option.value} value={option.value}>{option.label}</option>
                      ))}
                    </select>
                  </label>
                  <label className="block">
                    <span className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">主问题数</span>
                    <select
                      value={mainQuestionCount}
                      onChange={event => setMainQuestionCount(parseInt(event.target.value, 10))}
                      className={INPUT_CLASS}
                    >
                      {MAIN_QUESTION_COUNT_OPTIONS.map(count => (
                        <option key={count} value={count}>{count} 道</option>
                      ))}
                    </select>
                  </label>
                  <label className="block">
                    <span className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">每题追问</span>
                    <select
                      value={followUpCount}
                      onChange={event => setFollowUpCount(parseInt(event.target.value, 10))}
                      className={INPUT_CLASS}
                    >
                      {FOLLOW_UP_COUNT_OPTIONS.map(count => (
                        <option key={count} value={count}>{count} 个</option>
                      ))}
                    </select>
                  </label>
                </div>

                <div className="rounded-lg bg-slate-50 dark:bg-slate-900 px-4 py-3 text-sm text-slate-600 dark:text-slate-300">
                  {loadingCategories ? (
                    <span className="inline-flex items-center gap-2">
                      <Loader2 className="w-4 h-4 animate-spin" /> 正在统计可用题目…
                    </span>
                  ) : (
                    <>
                      当前条件可用{' '}
                      <span className={`font-bold ${canStart ? 'text-primary-600 dark:text-primary-400' : 'text-red-500'}`}>
                        {availableCount}
                      </span>{' '}
                      道主问题
                      {!canStart && (
                        <span className="block mt-1 text-xs text-red-500">
                          需至少 {mainQuestionCount} 道，请启用更多题目或减少题量
                        </span>
                      )}
                    </>
                  )}
                  {loadError && <p className="mt-1 text-xs text-red-500">{loadError}</p>}
                </div>

                {error && (
                  <div className="flex items-start gap-2 text-sm text-red-500">
                    <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
                    <span>{error}</span>
                  </div>
                )}
              </div>

              <div className="flex gap-3 justify-end px-6 py-4 border-t border-slate-100 dark:border-slate-700">
                <button
                  type="button"
                  onClick={onClose}
                  disabled={starting}
                  className="px-5 py-2.5 border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 rounded-xl font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors disabled:opacity-50"
                >
                  取消
                </button>
                <motion.button
                  type="button"
                  onClick={() => onStart({ category, difficulty, mainQuestionCount, followUpCount })}
                  disabled={!canStart || starting || loadingCategories}
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                  className="px-5 py-2.5 inline-flex items-center gap-2 text-white rounded-xl font-semibold shadow-lg bg-gradient-to-r from-primary-500 to-primary-600 hover:from-primary-600 hover:to-primary-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {starting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                  {starting ? '创建中…' : '开始面试'}
                </motion.button>
              </div>
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  );
}
