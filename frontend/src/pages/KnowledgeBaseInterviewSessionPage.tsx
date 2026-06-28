import { useEffect, useState } from 'react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { interviewApi } from '../api/interview';
import Interview from './InterviewPage';

interface SessionLocationState {
  knowledgeBaseId?: number;
}

export default function KnowledgeBaseInterviewSessionPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const stateKbId = (location.state as SessionLocationState | undefined)?.knowledgeBaseId;
  const [kbId, setKbId] = useState<number | undefined>(stateKbId);

  useEffect(() => {
    if (stateKbId !== undefined) {
      setKbId(stateKbId);
      return;
    }
    if (!sessionId) return;
    let cancelled = false;
    interviewApi
      .getSession(sessionId)
      .then(session => {
        if (!cancelled && session.knowledgeBaseId) {
          setKbId(session.knowledgeBaseId);
        }
      })
      .catch(() => {
        // 读取失败走默认兜底，不影响面试主流程
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, stateKbId]);

  if (!sessionId) {
    return <Navigate to="/knowledgebase-interview" replace />;
  }

  const backTarget = kbId
    ? `/knowledgebase-interview/${kbId}/questions`
    : '/knowledgebase-interview';

  const completeTarget = kbId
    ? `/knowledgebase-interview/${kbId}/questions`
    : '/interviews';

  return (
    <Interview
      resumeText=""
      sessionIdToResume={sessionId}
      title="知识库面试"
      subtitle="从已启用题库抽题，按题目评分规则评估"
      loadingText="正在加载知识库面试..."
      onBack={() => navigate(backTarget)}
      onInterviewComplete={() => navigate(completeTarget)}
    />
  );
}
