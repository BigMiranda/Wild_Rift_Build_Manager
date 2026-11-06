import { useState } from 'react';
import { t } from '../i18n.js';
import { useTasks } from '../tasks.js';
import { navigate } from '../router.js';

/** The global indicator of long jobs still running on any screen (they keep running while another tab is open). */
export default function TaskBell() {
  const tasks = useTasks();
  const [open, setOpen] = useState(false);
  return (
    <span className="task-bell">
      <button className={`icon${tasks.length ? ' busy' : ''}`} onClick={() => setOpen((o) => !o)} aria-expanded={open}
        title={tasks.length ? t('tasks.running', { n: tasks.length }) : t('tasks.none')} aria-label={t('tasks.title')}>
        🔔{tasks.length > 0 && <span className="task-count">{tasks.length}</span>}
      </button>
      {open && (
        <div className="task-list" role="dialog" aria-label={t('tasks.title')}>
          {tasks.length === 0 && <p className="muted small">{t('tasks.none')}</p>}
          {tasks.map((x) => (
            <button key={x.id} className="task-row" onClick={() => { if (x.path) navigate(x.path); setOpen(false); }}>
              <span>{x.label}</span>
              {x.total > 0 && <progress max={x.total} value={x.done ?? 0} />}
              {x.total > 0 && <small className="muted">{x.done ?? 0}/{x.total}</small>}
            </button>
          ))}
        </div>
      )}
    </span>
  );
}
