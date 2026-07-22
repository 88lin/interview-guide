import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import {
  Archive,
  ArrowLeft,
  BarChart3,
  Check,
  Loader2,
  Plus,
  Search,
  Sparkles,
  Trash2,
  X,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  type KnowledgeBaseItem,
  type KnowledgeBaseQuestion,
  type KnowledgeBaseQuestionStatus,
} from '../api/knowledgebase';
import {
  DEFAULT_DIFFICULTY,
  DEFAULT_CATEGORY_LIMIT,
  DIFFICULTY_OPTIONS,
  INPUT_CLASS,
} from '../constants/knowledgebaseInterview';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import QuestionFormDrawer, {
  type QuestionFormState,
  buildQuestionPayload,
  emptyQuestionForm,
  formFromQuestion,
} from '../components/knowledgebaseInterview/QuestionFormDrawer';
import GenerateKnowledgeBaseQuestionsModal, {
  type GenerateQuestionsConfig,
} from '../components/knowledgebaseInterview/GenerateKnowledgeBaseQuestionsModal';
import QuestionCard from '../components/knowledgebaseInterview/QuestionCard';

interface QuestionFilters {
  status: KnowledgeBaseQuestionStatus | '';
  category: string;
  difficulty: string;
  keyword: string;
}

const EMPTY_FILTERS: QuestionFilters = {
  status: '',
  category: '',
  difficulty: '',
  keyword: '',
};

const STATUS_TABS: Array<{ value: KnowledgeBaseQuestionStatus | ''; label: string }> = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'ACTIVE', label: '已启用' },
  { value: 'ARCHIVED', label: '已归档' },
];

interface LocationState {
  highlightStatus?: KnowledgeBaseQuestionStatus;
  generationMessage?: string;
  generationWarning?: boolean;
}

export default function KnowledgeBaseInterviewQuestionsPage() {
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const routeState = location.state as LocationState | undefined;
  const initialHighlight = routeState?.highlightStatus;

  const knowledgeBaseIdNum = knowledgeBaseId ? parseInt(knowledgeBaseId, 10) : NaN;

  const [knowledgeBase, setKnowledgeBase] = useState<KnowledgeBaseItem | null>(null);
  const [loadingKb, setLoadingKb] = useState(true);

  const [allQuestions, setAllQuestions] = useState<KnowledgeBaseQuestion[]>([]);
  const [questions, setQuestions] = useState<KnowledgeBaseQuestion[]>([]);
  const [loadingQuestions, setLoadingQuestions] = useState(false);
  const [categoryOptions, setCategoryOptions] = useState<string[]>([]);

  const [filters, setFilters] = useState<QuestionFilters>({
    ...EMPTY_FILTERS,
    status: initialHighlight ?? '',
  });

  const [generateOpen, setGenerateOpen] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState('');
  const [generationMessage, setGenerationMessage] = useState(routeState?.generationMessage || '');
  const [generationWarning, setGenerationWarning] = useState(routeState?.generationWarning || false);

  const [formOpen, setFormOpen] = useState(false);
  const [editingQuestion, setEditingQuestion] = useState<KnowledgeBaseQuestion | null>(null);
  const [form, setForm] = useState<QuestionFormState>(emptyQuestionForm());
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);

  const [deleteQuestion, setDeleteQuestion] = useState<KnowledgeBaseQuestion | null>(null);
  const [batchDeleteOpen, setBatchDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [actionError, setActionError] = useState('');

  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [batchRunning, setBatchRunning] = useState(false);

  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);

  const loadKnowledgeBase = useCallback(async () => {
    if (Number.isNaN(knowledgeBaseIdNum)) {
      setLoadingKb(false);
      return;
    }
    setLoadingKb(true);
    try {
      const detail = await knowledgeBaseApi.getKnowledgeBase(knowledgeBaseIdNum);
      setKnowledgeBase(detail);
    } catch {
      setKnowledgeBase(null);
    } finally {
      setLoadingKb(false);
    }
  }, [knowledgeBaseIdNum]);

  const loadQuestions = useCallback(async () => {
    if (Number.isNaN(knowledgeBaseIdNum)) {
      setAllQuestions([]);
      setQuestions([]);
      setCategoryOptions([]);
      return;
    }
    setLoadingQuestions(true);
    try {
      const [all, filtered] = await Promise.all([
        knowledgeBaseApi.listQuestions(knowledgeBaseIdNum),
        knowledgeBaseApi.listQuestions(knowledgeBaseIdNum, filters),
      ]);
      setAllQuestions(all);
      setQuestions(filtered);
      // 基于全量题目本地聚合并排序方向，避免每次筛选都额外请求 distinct 接口
      const categories = new Set<string>();
      all.forEach(q => {
        if (q.category) categories.add(q.category);
      });
      setCategoryOptions(Array.from(categories).sort((a, b) => a.localeCompare(b)));
    } finally {
      setLoadingQuestions(false);
    }
  }, [filters, knowledgeBaseIdNum]);

  useEffect(() => {
    loadKnowledgeBase();
  }, [loadKnowledgeBase]);

  useEffect(() => {
    loadQuestions();
  }, [loadQuestions]);

  // 切换筛选/Tab 时清空已选并回到第一页
  useEffect(() => {
    setSelectedIds(new Set());
    setPage(0);
  }, [filters]);

  const statusCounts = useMemo(() => {
    return allQuestions.reduce<Record<KnowledgeBaseQuestionStatus, number>>(
      (counts, q) => {
        counts[q.status] += 1;
        return counts;
      },
      { DRAFT: 0, ACTIVE: 0, ARCHIVED: 0, STALE: 0 }
    );
  }, [allQuestions]);

  const totalPages = Math.max(1, Math.ceil(questions.length / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const pagedQuestions = useMemo(
    () => questions.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [questions, safePage, pageSize]
  );
  const allOnPageSelected = pagedQuestions.length > 0 && pagedQuestions.every(q => selectedIds.has(q.id));

  const toggleSelectAll = () => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      pagedQuestions.forEach(q => {
        if (allOnPageSelected) next.delete(q.id);
        else next.add(q.id);
      });
      return next;
    });
  };

  const toggleSelectOne = (id: number) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const clearSelection = () => setSelectedIds(new Set());

  const openCreateForm = () => {
    setEditingQuestion(null);
    setForm(emptyQuestionForm({
      category: filters.category,
      difficulty: filters.difficulty || DEFAULT_DIFFICULTY,
    }));
    setFormError('');
    setFormOpen(true);
  };

  const openEditForm = (question: KnowledgeBaseQuestion) => {
    setEditingQuestion(question);
    setForm(formFromQuestion(question));
    setFormError('');
    setFormOpen(true);
  };

  const handleGenerate = async (config: GenerateQuestionsConfig) => {
    if (Number.isNaN(knowledgeBaseIdNum)) return;
    setGenerating(true);
    setGenerateError('');
    try {
      const result = await knowledgeBaseApi.generateQuestions(knowledgeBaseIdNum, {
        difficulty: config.difficulty,
        questionCount: config.questionCount,
        followUpCount: config.followUpCount,
        categoryLimit: config.categoryLimit,
      });
      setGenerationMessage(result.message);
      setGenerationWarning(result.saved.length === 0 && result.skipped > 0);
      setGenerateOpen(false);
      // 生成后只过滤出"草稿"，方向不限定，方便一次性看到新生成的题目
      setFilters({ status: 'DRAFT', category: '', difficulty: config.difficulty, keyword: '' });
      await loadQuestions();
    } catch (error) {
      setGenerateError(error instanceof Error ? error.message : '生成失败，请稍后重试');
    } finally {
      setGenerating(false);
    }
  };

  const handleSaveQuestion = async (event: FormEvent) => {
    event.preventDefault();
    if (Number.isNaN(knowledgeBaseIdNum)) return;
    const payload = buildQuestionPayload(form);
    if (!payload.question) {
      setFormError('题干不能为空');
      return;
    }
    if (!payload.category) {
      setFormError('面试方向不能为空');
      return;
    }
    setSaving(true);
    try {
      if (editingQuestion) {
        await knowledgeBaseApi.updateQuestion(editingQuestion.id, payload);
      } else {
        await knowledgeBaseApi.createQuestion(knowledgeBaseIdNum, payload);
      }
      setFormOpen(false);
      await loadQuestions();
    } catch (error) {
      setFormError(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteQuestion = async () => {
    if (!deleteQuestion) return;
    setDeleting(true);
    setActionError('');
    try {
      await knowledgeBaseApi.deleteQuestion(deleteQuestion.id);
      setDeleteQuestion(null);
      await loadQuestions();
    } catch (error) {
      setActionError(error instanceof Error ? error.message : '删除失败');
    } finally {
      setDeleting(false);
    }
  };

  const handleUpdateStatus = async (questionId: number, status: KnowledgeBaseQuestionStatus) => {
    setActionError('');
    try {
      await knowledgeBaseApi.updateQuestionStatus(questionId, status);
      await loadQuestions();
    } catch (error) {
      setActionError(error instanceof Error ? error.message : '更新状态失败');
    }
  };

  const handleBatchUpdateStatus = async (status: KnowledgeBaseQuestionStatus) => {
    if (selectedIds.size === 0) return;
    setBatchRunning(true);
    setActionError('');
    const ids = Array.from(selectedIds);
    let failed = 0;
    await Promise.all(
      ids.map(id =>
        knowledgeBaseApi.updateQuestionStatus(id, status).catch(() => {
          failed += 1;
        })
      )
    );
    setBatchRunning(false);
    if (failed > 0) {
      setActionError(`${failed} 道题目更新失败，请重试`);
    }
    setSelectedIds(new Set());
    await loadQuestions();
  };

  const handleBatchDelete = async () => {
    if (selectedIds.size === 0) return;
    setBatchRunning(true);
    setActionError('');
    const ids = Array.from(selectedIds);
    let failed = 0;
    await Promise.all(
      ids.map(id =>
        knowledgeBaseApi.deleteQuestion(id).catch(() => {
          failed += 1;
        })
      )
    );
    setBatchRunning(false);
    setBatchDeleteOpen(false);
    if (failed > 0) {
      setActionError(`${failed} 道题目删除失败，请重试`);
    }
    setSelectedIds(new Set());
    await loadQuestions();
  };

  if (loadingKb) {
    return (
      <div className="flex justify-center py-24">
        <Loader2 className="w-8 h-8 animate-spin text-primary-500" />
      </div>
    );
  }

  if (Number.isNaN(knowledgeBaseIdNum) || !knowledgeBase) {
    return (
      <div className="max-w-3xl mx-auto flex flex-col items-center justify-center min-h-[320px] gap-4 text-center">
        <p className="text-slate-500 dark:text-slate-400">知识库不存在或已被删除</p>
        <button
          onClick={() => navigate('/knowledgebase-interview')}
          className="px-4 py-2 rounded-lg bg-primary-500 text-white text-sm font-medium hover:bg-primary-600"
        >
          返回首页
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-[1400px] mx-auto">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between mb-6">
        <div className="min-w-0">
          <button
            onClick={() => navigate('/knowledgebase-interview')}
            className="inline-flex items-center gap-1.5 text-sm text-slate-500 dark:text-slate-400 hover:text-primary-500 mb-2"
          >
            <ArrowLeft className="w-4 h-4" />
            返回知识库面试
          </button>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white truncate">
            题库管理：{knowledgeBase.name}
          </h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1 truncate">
            维护题干、答案、追问与状态
          </p>
        </div>
        <div className="flex gap-2 shrink-0">
          <button
            onClick={() => navigate(`/knowledgebase-interview/${knowledgeBaseIdNum}/interviews`)}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg bg-indigo-500 text-white text-sm font-medium hover:bg-indigo-600 shadow-sm shadow-indigo-500/20 whitespace-nowrap"
          >
            <BarChart3 className="w-4 h-4" />
            查看面试记录
          </button>
          <button
            onClick={() => setGenerateOpen(true)}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg border border-primary-200 dark:border-primary-800 text-primary-600 dark:text-primary-400 text-sm font-medium hover:bg-primary-50 dark:hover:bg-primary-900/20 whitespace-nowrap"
          >
            <Sparkles className="w-4 h-4" />
            生成题目
          </button>
          <button
            onClick={openCreateForm}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg bg-primary-500 text-white text-sm font-medium hover:bg-primary-600 whitespace-nowrap"
          >
            <Plus className="w-4 h-4" />
            手动新增
          </button>
        </div>
      </div>

      <div className="flex flex-wrap gap-2 mb-4">
        {STATUS_TABS.map(tab => {
          const count = tab.value === ''
            ? allQuestions.length
            : statusCounts[tab.value] ?? 0;
          const active = filters.status === tab.value;
          return (
            <button
              key={tab.value || 'all'}
              onClick={() => setFilters(prev => ({ ...prev, status: tab.value }))}
              className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
                active
                  ? 'bg-primary-500 text-white'
                  : 'bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700'
              }`}
            >
              {tab.label}
              <span className={`px-1.5 py-0.5 rounded text-xs ${active ? 'bg-white/20' : 'bg-slate-100 dark:bg-slate-700 text-slate-500'}`}>
                {count}
              </span>
            </button>
          );
        })}
      </div>

      <div className="bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-700 rounded-xl p-4 mb-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-3">
          <div className="relative">
            <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
            <input
              value={filters.keyword}
              onChange={event => setFilters(prev => ({ ...prev, keyword: event.target.value }))}
              className={`${INPUT_CLASS} pl-9`}
              placeholder="搜索题干 / 参考答案"
            />
          </div>
          <select
            value={filters.category}
            onChange={event => setFilters(prev => ({ ...prev, category: event.target.value }))}
            className={INPUT_CLASS}
          >
            <option value="">全部方向</option>
            {categoryOptions.map(category => (
              <option key={category} value={category}>{category}</option>
            ))}
          </select>
          <select
            value={filters.difficulty}
            onChange={event => setFilters(prev => ({ ...prev, difficulty: event.target.value }))}
            className={INPUT_CLASS}
          >
            <option value="">全部难度</option>
            {DIFFICULTY_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
          <div className="flex gap-2">
            <button
              onClick={() => setFilters(EMPTY_FILTERS)}
              className="inline-flex min-h-10 flex-1 items-center justify-center gap-2 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 whitespace-nowrap"
            >
              <X className="w-4 h-4" />
              清空
            </button>
          </div>
        </div>
      </div>

      {questions.length > 0 && (
        <div className="flex flex-wrap items-center gap-3 mb-4 px-1">
          <label className="inline-flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300 cursor-pointer">
            <input
              type="checkbox"
              checked={allOnPageSelected}
              onChange={toggleSelectAll}
              className="w-4 h-4 rounded border-slate-300 text-primary-500 focus:ring-primary-500/30"
            />
            {allOnPageSelected ? '取消本页全选' : '选择本页全部'}
          </label>
          {selectedIds.size > 0 && (
            <>
              <span className="text-sm text-slate-500 dark:text-slate-400">
                已选 <span className="font-semibold text-primary-600 dark:text-primary-400">{selectedIds.size}</span> 道
              </span>
              <div className="flex gap-2">
                <button
                  onClick={() => handleBatchUpdateStatus('ACTIVE')}
                  disabled={batchRunning}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 dark:border-emerald-800 text-emerald-600 dark:text-emerald-400 text-xs font-medium hover:bg-emerald-50 dark:hover:bg-emerald-900/20 disabled:opacity-50 whitespace-nowrap"
                >
                  <Check className="w-3.5 h-3.5" />
                  批量启用
                </button>
                <button
                  onClick={() => handleBatchUpdateStatus('ARCHIVED')}
                  disabled={batchRunning}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 whitespace-nowrap"
                >
                  <Archive className="w-3.5 h-3.5" />
                  批量归档
                </button>
                <button
                  onClick={() => setBatchDeleteOpen(true)}
                  disabled={batchRunning}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-red-200 dark:border-red-900 text-red-600 dark:text-red-400 text-xs font-medium hover:bg-red-50 dark:hover:bg-red-900/20 disabled:opacity-50 whitespace-nowrap"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  批量删除
                </button>
                <button
                  onClick={clearSelection}
                  disabled={batchRunning}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 text-xs whitespace-nowrap"
                >
                  <X className="w-3.5 h-3.5" />
                  清空选择
                </button>
              </div>
              {batchRunning && <Loader2 className="w-4 h-4 animate-spin text-primary-500" />}
            </>
          )}
        </div>
      )}

      <div className="space-y-3">
        {generationMessage && (
          <p className={`text-sm ${
            generationWarning
              ? 'text-amber-600 dark:text-amber-400'
              : 'text-emerald-600 dark:text-emerald-400'
          }`}>
            {generationMessage}
          </p>
        )}
        {actionError && <p className="text-sm text-red-500">{actionError}</p>}
        {loadingQuestions ? (
          <div className="flex justify-center py-16">
            <Loader2 className="w-8 h-8 animate-spin text-primary-500" />
          </div>
        ) : questions.length === 0 ? (
          <div className="flex min-h-[220px] items-center justify-center rounded-xl border border-dashed border-slate-200 dark:border-slate-700 text-slate-400 flex-col gap-3">
            <p>当前条件下暂无题目</p>
            <button
              onClick={() => setGenerateOpen(true)}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-primary-500 text-white text-sm font-medium hover:bg-primary-600"
            >
              <Sparkles className="w-4 h-4" />
              生成第一批题目
            </button>
          </div>
        ) : (
          <>
            {pagedQuestions.map(question => (
              <QuestionCard
                key={question.id}
                question={question}
                selected={selectedIds.has(question.id)}
                onSelect={toggleSelectOne}
                onEdit={openEditForm}
                onUpdateStatus={handleUpdateStatus}
                onDelete={q => setDeleteQuestion(q)}
              />
            ))}
            <Pagination
              page={safePage}
              pageSize={pageSize}
              total={questions.length}
              totalPages={totalPages}
              onPageChange={setPage}
              onPageSizeChange={size => {
                setPageSize(size);
                setPage(0);
              }}
            />
          </>
        )}
      </div>

      {formOpen && (
        <QuestionFormDrawer
          form={form}
          editing={editingQuestion !== null}
          saving={saving}
          error={formError}
          categoryOptions={categoryOptions}
          onChange={setForm}
          onClose={() => setFormOpen(false)}
          onSubmit={handleSaveQuestion}
        />
      )}

      <GenerateKnowledgeBaseQuestionsModal
        open={generateOpen}
        knowledgeBaseName={knowledgeBase.name}
        defaultDifficulty={DEFAULT_DIFFICULTY}
        defaultCategoryLimit={DEFAULT_CATEGORY_LIMIT}
        generating={generating}
        error={generateError}
        onClose={() => {
          if (!generating) {
            setGenerateOpen(false);
            setGenerateError('');
          }
        }}
        onSubmit={handleGenerate}
      />

      <DeleteConfirmDialog
        open={deleteQuestion !== null}
        item={deleteQuestion ? { id: deleteQuestion.id, title: deleteQuestion.question } : null}
        itemType="题目"
        loading={deleting}
        onConfirm={handleDeleteQuestion}
        onCancel={() => setDeleteQuestion(null)}
      />
      <DeleteConfirmDialog
        open={batchDeleteOpen}
        item={null}
        itemType="题目"
        loading={batchRunning}
        customMessage={
          <span>
            确定要删除已选的 <strong>{selectedIds.size}</strong> 道题目吗？删除后无法恢复。
          </span>
        }
        onConfirm={handleBatchDelete}
        onCancel={() => setBatchDeleteOpen(false)}
      />
    </div>
  );
}

const PAGE_SIZE_OPTIONS = [10, 20, 50];

interface PaginationProps {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
}

function Pagination({
  page,
  pageSize,
  total,
  totalPages,
  onPageChange,
  onPageSizeChange,
}: PaginationProps) {
  if (total === 0) return null;
  const from = page * pageSize + 1;
  const to = Math.min((page + 1) * pageSize, total);

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 mt-4 px-1 text-sm">
      <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400">
        <span>
          第 <span className="font-semibold text-slate-700 dark:text-slate-200">{from}-{to}</span> /
          共 <span className="font-semibold text-slate-700 dark:text-slate-200">{total}</span> 道
        </span>
        <span className="text-slate-300 dark:text-slate-600">|</span>
        <span>每页</span>
        <select
          value={pageSize}
          onChange={event => onPageSizeChange(parseInt(event.target.value, 10))}
          className="px-2 py-1 rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-sm text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-primary-500/20"
        >
          {PAGE_SIZE_OPTIONS.map(size => (
            <option key={size} value={size}>{size}</option>
          ))}
        </select>
      </div>
      <div className="flex items-center gap-1">
        <PaginationButton
          disabled={page === 0}
          onClick={() => onPageChange(0)}
          title="第一页"
        >
          «
        </PaginationButton>
        <PaginationButton
          disabled={page === 0}
          onClick={() => onPageChange(page - 1)}
          title="上一页"
        >
          ‹
        </PaginationButton>
        <span className="px-3 text-slate-700 dark:text-slate-200">
          {page + 1} / {totalPages}
        </span>
        <PaginationButton
          disabled={page >= totalPages - 1}
          onClick={() => onPageChange(page + 1)}
          title="下一页"
        >
          ›
        </PaginationButton>
        <PaginationButton
          disabled={page >= totalPages - 1}
          onClick={() => onPageChange(totalPages - 1)}
          title="最后一页"
        >
          »
        </PaginationButton>
      </div>
    </div>
  );
}

function PaginationButton({
  children,
  disabled,
  onClick,
  title,
}: {
  children: React.ReactNode;
  disabled: boolean;
  onClick: () => void;
  title: string;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      className="w-9 h-9 inline-flex items-center justify-center rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-40 disabled:cursor-not-allowed"
    >
      {children}
    </button>
  );
}
