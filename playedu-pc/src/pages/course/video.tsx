// Teaching customization (2026-11-01): automatic resume and independent playback controls.
import { bindLearningPlayer } from "../../utils/playback";
import { bindPlayerSubtitles, SubtitleTrack } from "../../utils/player-subtitles";
import { useEffect, useRef, useState } from "react";
import styles from "./video.module.scss";
import { useParams, useNavigate } from "react-router-dom";
import { useSelector } from "react-redux";
import { course as Course } from "../../api/index";
import { ArrowLeftOutlined } from "@ant-design/icons";
import { message } from "antd";
import { getPlayId, savePlayId } from "../../utils";

declare const window: any;
var timer: any = null;

const CoursePalyPage = () => {
  const navigate = useNavigate();
  const params = useParams();
  const systemConfig = useSelector((state: any) => state.systemConfig.value);
  const user = useSelector((state: any) => state.loginUser.value.user);
  const [playUrl, setPlayUrl] = useState("");
  const [playendedStatus, setPlayendedStatus] = useState(false);
  const [lastSeeValue, setLastSeeValue] = useState({});
  const [course, setCourse] = useState<CourseModel | null>(null);
  const [hour, setHour] = useState<HourModel | null>(null);
  const [loading, setLoading] = useState(false);
  const [isLastpage, setIsLastpage] = useState(false);
  const [totalHours, setTotalHours] = useState<HourModel[]>([]);
  const [playingTime, setPlayingTime] = useState(0);
  const [watchedSeconds, setWatchedSeconds] = useState(0);
  const [resourceUrl, setResourceUrl] = useState<ResourceUrlModel>({});
  const playRef = useRef(0);
  const watchRef = useRef(0);
  const totalRef = useRef(0);
  const [checkPlayerStatus, setCheckPlayerStatus] = useState(false);

  useEffect(() => {
    timer && clearInterval(timer);
    window.player && window.player.destroy();
    setPlayendedStatus(false);
    getCourse();
    getDetail();
    return () => {
      timer && clearInterval(timer);
      window.player && window.player.destroy();
    };
  }, [params.courseId, params.hourId]);

  useEffect(() => {
    playRef.current = playingTime;
  }, [playingTime]);

  useEffect(() => {
    watchRef.current = watchedSeconds;
  }, [watchedSeconds]);

  useEffect(() => {
    totalRef.current = hour?.duration || 0;
  }, [hour]);

  const getCourse = () => {
    Course.detail(Number(params.courseId)).then((res: any) => {
      if (Number(window.location.pathname.split('/')[2]) !== Number(params.courseId) ||
          Number(window.location.pathname.split('/')[4]) !== Number(params.hourId)) return;
      const totalHours = res.data.ordered_hours || [];
      setTotalHours(totalHours);
      const index = totalHours.findIndex(
        (i: any) => i.id === Number(params.hourId)
      );
      setIsLastpage(index === totalHours.length - 1);
    });
  };

  const getDetail = () => {
    setLoading(true);
    Course.play(Number(params.courseId), Number(params.hourId))
      .then((res: any) => {
        if (Number(window.location.pathname.split('/')[2]) !== Number(params.courseId) ||
            Number(window.location.pathname.split('/')[4]) !== Number(params.hourId)) return;
        setCourse(res.data.course);
        setHour(res.data.hour);
        document.title = res.data.hour.title;
        const record = res.data.user_hour_record;
        const position = res.data.playback_position?.position ??
          (record?.is_finished === 1 ? 0 : record?.finished_duration || 0);
        const playback = { courseId: Number(params.courseId), hourId: Number(params.hourId),
          pos: position, maximum: record?.finished_duration || 0,
          total: res.data.hour.duration, completed: record?.is_finished === 1 };
        setWatchedSeconds(record?.finished_duration || 0);
        getVideoUrl(res.data.hour.rid, playback);
        setLoading(false);
      })
      .catch((e) => {
        setLoading(false);
        if (Number(window.location.pathname.split('/')[2]) === Number(params.courseId) &&
            Number(window.location.pathname.split('/')[4]) === Number(params.hourId)) {
          navigate(`/course/${params.courseId}`, { replace: true });
        }
      });
  };

  const getVideoUrl = (rid: number, data: any) => {
    Course.playUrl(Number(params.courseId), Number(params.hourId)).then(
      (res: any) => {
        if (Number(window.location.pathname.split('/')[2]) !== data.courseId ||
            Number(window.location.pathname.split('/')[4]) !== data.hourId) return;
        setResourceUrl(res.data.resource_url[rid]);
        setPlayUrl(res.data.resource_url[rid]);
        initDPlayer(res.data.resource_url[rid], 0, data, res.data.subtitle_tracks);
        savePlayId(String(params.courseId) + "-" + String(params.hourId));
      }
    );
  };

  const initDPlayer = (playUrl: string, isTrySee: number, params: any, subtitles: SubtitleTrack[] = []) => {
    const banDrag = !!systemConfig.playerIsDisabledDrag;
    window.player = new window.DPlayer({
      container: document.getElementById("meedu-player-container"),
      autoplay: false,
      video: {
        url: playUrl,
        pic: systemConfig.playerPoster,
      },
      try: isTrySee === 1,
      bulletSecret: {
        enabled: systemConfig.playerIsEnabledBulletSecret,
        text: systemConfig.playerBulletSecretText
          .replace("{name}", user.name)
          .replace("{email}", user.email)
          .replace("{idCard}", user.id_card),
        size: "14px",
        color: systemConfig.playerBulletSecretColor || "red",
        opacity: Number(systemConfig.playerBulletSecretOpacity),
      },
      ban_drag: banDrag,
      playbackSpeed: systemConfig.playerIsDisabledSpeed ? [1] : [0.5, 0.75, 1, 1.25, 1.5, 2],
    });
    bindLearningPlayer(window.player, {
      courseId: params.courseId,
      hourId: params.hourId,
      total: params.total, initial: systemConfig.playerAutoResume === false ? 0 : params.pos,
      completed: params.completed ? params.total : params.maximum,
      onCompleted: getCourse,
      allowSeek: !systemConfig.playerIsDisabledDrag,
      allowSpeed: !systemConfig.playerIsDisabledSpeed,
      notify: (text) => message.warning(text),
    });
    bindPlayerSubtitles(window.player, subtitles, (text) => message.warning(text));
    window.player.on('ended', () => {
      setPlayendedStatus(true);
      setPlayingTime(0);
      exitFullscreen();
    });
    setLoading(false);
    checkPlayer();
  };

  const checkPlayer = () => {
    timer = setInterval(() => {
      let playId = getPlayId();
      if (
        playId &&
        playId !== String(params.courseId) + "-" + String(params.hourId)
      ) {
        timer && clearInterval(timer);
        window.player && window.player.destroy();
        setCheckPlayerStatus(true);
      } else {
        setCheckPlayerStatus(false);
      }
    }, 5000);
  };

  const goNextVideo = () => {
    const index = totalHours.findIndex(
      (i: any) => i.id === Number(params.hourId)
    );
    if (index === totalHours.length - 1) {
      setIsLastpage(true);
      message.error("已经是最后一节了！");
    } else if (index >= 0 && index < totalHours.length - 1) {
      const nextId = totalHours[index + 1].id;
      Course.play(Number(params.courseId), nextId).then(() => {
        if (Number(window.location.pathname.split('/')[2]) !== Number(params.courseId) ||
            Number(window.location.pathname.split('/')[4]) !== Number(params.hourId)) return;
        navigate(`/course/${params.courseId}/hour/${nextId}`, { replace: true });
      }).catch(() => {});
    }
  };

  const exitFullscreen = () => {
    if (window.player?.fullScreen?.isFullScreen("web")) {
      window.player.fullScreen.cancel("web");
      return;
    }
    let de: any;
    de = document;
    if (de.fullscreenElement !== null) {
      de.exitFullscreen();
    } else if (de.mozCancelFullScreen) {
      de.mozCancelFullScreen();
    } else if (de.webkitCancelFullScreen) {
      de.webkitCancelFullScreen();
    }
  };

  return (
    <div className={styles["video-mask"]}>
      <div className={styles["top-cont"]}>
        <div className={styles["box"]}>
          <div
            className={styles["close-btn"]}
            onClick={() => {
              timer && clearInterval(timer);
              window.player && window.player.destroy();
              navigate(`/course/${params.courseId}`, { replace: true });
            }}
          >
            <ArrowLeftOutlined />
            <span className="ml-14">返回</span>
          </div>
        </div>
      </div>
      <div className={styles["video-body"]}>
        <div className={styles["video-title"]}>{hour?.title}</div>
        <div className={styles["video-box"]}>
          <div
            className="play-box"
            id="meedu-player-container"
            style={{ borderRadius: 8 }}
          ></div>
          {checkPlayerStatus && (
            <div className={styles["alert-message"]}>
              <div className={styles["des-video"]}>
                您已打开新视频，暂停本视频播放
              </div>
            </div>
          )}
          {playendedStatus && (
            <div className={styles["alert-message"]}>
              {isLastpage && (
                <div
                  className={styles["alert-button"]}
                  onClick={() => navigate(`/course/${params.courseId}`)}
                >
                  恭喜你学完最后一节
                </div>
              )}
              {!isLastpage && (
                <div
                  className={styles["alert-button"]}
                  onClick={() => {
                    goNextVideo();
                  }}
                >
                  播放下一节
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default CoursePalyPage;
