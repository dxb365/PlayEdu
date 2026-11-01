// Teaching customization: restore once per site opening; keep homepage navigation available.
import { latestPlayback } from "./utils/playback";
import { getToken } from "./utils";
let resumeChecked = false;
import { useEffect, Suspense } from "react";
import { useRoutes, useLocation, useNavigate } from "react-router-dom";
import routes from "./routes";
import "./App.scss";
import LoadingPage from "./pages/loading";

const App = () => {
  const views = useRoutes(routes);
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => {
    if (resumeChecked || location.pathname !== '/' || !getToken()) return;
    resumeChecked = true;
    const base = (import.meta.env.VITE_APP_URL || '').replace(/\/$/, '');
    void fetch(base + '/api/v1/system/config').then(r => r.json()).then(config => {
      if (config.data?.['player-auto-resume'] === '0') return null;
      return latestPlayback();
    }).then(row => {
      if (row?.course_id && row?.hour_id && window.location.pathname === '/')
        navigate('/course/' + row.course_id + '/hour/' + row.hour_id, { replace: true });
    }).catch(() => {});
  }, [location.pathname, navigate]);

  return <Suspense fallback={<LoadingPage />}>{views}</Suspense>;
};

export default App;
