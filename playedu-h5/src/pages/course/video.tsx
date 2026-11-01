// Teaching customization (2026-11-01): automatic resume and independent playback controls.
import { bindLearningPlayer } from "../../utils/playback";
import { useEffect, useRef, useState } from "react";
import styles from "./video.module.scss";
import { useParams, useNavigate } from "react-router-dom";
import { useSelector } from "react-redux";
import { course as Course } from "../../api/index";
import { Toast, Image, Dialog } from "antd-mobile";
import backIcon from "../../assets/images/commen/icon-back-n.png";
import { Empty } from "../../components";
import { HourCompenent } from "./compenents/videoHour";

declare const window: any;

/**
 * 检测是否为 iOS 设备
 */
const isIOS = (): boolean => {
  return /iPad|iPhone|iPod/.test(navigator.userAgent);
};

/**
 * 检测是否为夸克浏览器
 */
const isQuarkBrowser = (): boolean => {
  const ua = navigator.userAgent.toLowerCase();
  return ua.includes("quark") || ua.includes("qbrowse");
};

/**
 * 检测是否为 iOS 夸克浏览器
 */
const isIOSQuarkBrowser = (): boolean => {
  return isIOS() && isQuarkBrowser();
};

type LocalUserLearnHourRecordModel = {
  [key: number]: UserLearnHourRecordModel;
};

type LocalCourseHour = {
  [key: number]: CourseHourModel[];
};

const CoursePlayPage = () => {
  const navigate = useNavigate();
  const params = useParams();
  const systemConfig = useSelector((state: any) => state.systemConfig.value);
  const user = useSelector((state: any) => state.loginUser.value.user);
  const [playUrl, setPlayUrl] = useState("");
  const [playendedStatus, setPlayendedStatus] = useState(false);
  const [lastSeeValue, setLastSeeValue] = useState({});
  const [course, setCourse] = useState<CourseModel | null>(null);
  const [hour, setHour] = useState<any>({});
  const [loading, setLoading] = useState(false);
  const [isLastpage, setIsLastpage] = useState(false);
  const [totalHours, setTotalHours] = useState<any>([]);
  const [playingTime, setPlayingTime] = useState(0);
  const [watchedSeconds, setWatchedSeconds] = useState(0);
  const [chapters, setChapters] = useState<ChapterModel[]>([]);
  const [hours, setHours] = useState<LocalCourseHour | null>(null);
  const [learnHourRecord, setLearnHourRecord] =
    useState<LocalUserLearnHourRecordModel>({});
  const playRef = useRef(0);
  const watchRef = useRef(0);
  const totalRef = useRef(0);
  const hourIdRef = useRef<number>(0);

  useEffect(() => {
    // 检测是否为 iOS 夸克浏览器
    if (isIOSQuarkBrowser()) {
      Dialog.alert({
        content:
          "当前夸克浏览器环境下无法记录学习数据，请前往其他浏览器（如 Safari、Chrome）打开学习",
        confirmText: "知道了",
        onConfirm: () => {
          navigate(-1);
        },
      });
      return;
    }
    hourIdRef.current = Number(params.hourId);
    window.player && window.player.destroy();
    setPlayendedStatus(false);
    getCourse();
    getDetail();
    return () => { window.player && window.player.destroy(); };
  }, [params.courseId, params.hourId]);

  useEffect(() => {
    playRef.current = playingTime;
  }, [playingTime]);

  useEffect(() => {
    watchRef.current = watchedSeconds;
  }, [watchedSeconds]);

  useEffect(() => {
    totalRef.current = hour.duration;
  }, [hour]);

  const getCourse = () => {
    Course.detail(Number(params.courseId)).then((res: any) => {
      setChapters(res.data.chapters);
      setHours(res.data.hours);
      if (res.data.learn_hour_records) {
        setLearnHourRecord(res.data.learn_hour_records);
      }
      let totalHours: any = [];
      if (res.data.chapters.length === 0) {
        setTotalHours(res.data.hours[0]);
        totalHours = res.data.hours[0];
      } else if (res.data.chapters.length > 0) {
        const arr: any = [];
        for (let key in res.data.hours) {
          res.data.hours[key].map((item: any) => {
            arr.push(item);
          });
        }
        setTotalHours(arr);
        totalHours = arr;
      }
      //判断是否是最后的课时
      const index = totalHours.findIndex(
        (i: any) => i.id === Number(params.hourId),
      );
      if (index === totalHours.length - 1) {
        setIsLastpage(true);
      } else {
        setIsLastpage(false);
      }
    });
  };

  const getDetail = () => {
    if (loading) {
      return true;
    }
    setLoading(true);
    Course.play(Number(params.courseId), Number(params.hourId))
      .then((res: any) => {
        let courseItem: CourseModel = res.data.course;
        setCourse(courseItem);
        setHour(res.data.hour);
        document.title = res.data.hour.title;
        const record = res.data.user_hour_record;
        const position = res.data.playback_position?.position ??
          (record?.is_finished === 1 ? 0 : record?.finished_duration || 0);
        const params = { pos: position, maximum: record?.finished_duration || 0,
          total: res.data.hour.duration, completed: record?.is_finished === 1 };
        setWatchedSeconds(record?.finished_duration || 0);
        getVideoUrl(res.data.hour.rid, params);
        setLoading(false);
      })
      .catch((e) => {
        setLoading(false);
      });
  };

  const getVideoUrl = (rid: number, data: any) => {
    Course.playUrl(Number(params.courseId), Number(params.hourId)).then(
      (res: any) => {
        window.player && window.player.destroy();
        setPlayUrl(res.data.resource_url[rid]);
        initDPlayer(res.data.resource_url[rid], 0, data);
      },
    );
  };

  const initDPlayer = (playUrl: string, isTrySee: number, params: any) => {
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
      courseId: Number(window.location.pathname.split('/')[2]),
      hourId: Number(window.location.pathname.split('/')[4]),
      total: params.total, initial: systemConfig.playerAutoResume === false ? 0 : params.pos,
      completed: params.completed ? params.total : params.maximum,
      allowSeek: !systemConfig.playerIsDisabledDrag,
      allowSpeed: !systemConfig.playerIsDisabledSpeed,
      notify: (text) => Toast.show(text),
    });
    window.player.on('ended', () => {
      setPlayendedStatus(true);
      setPlayingTime(0);
      exitFullscreen();
    });
    setLoading(false);
  };

  const goNextVideo = () => {
    const index = totalHours.findIndex(
      (i: any) => i.id === Number(params.hourId),
    );
    if (index === totalHours.length - 1) {
      setIsLastpage(true);
      Toast.show("已经是最后一节了！");
    } else if (index < totalHours.length - 1) {
      setIsLastpage(false);
      navigate(`/course/${params.courseId}/hour/${totalHours[index + 1].id}`, {
        replace: true,
      });
    }
  };

  const exitFullscreen = () => {
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

  const playVideo = (cid: number, id: number) => {
    navigate(`/course/${cid}/hour/${id}`, { replace: true });
  };

  return (
    <div className="main-body">
      <div className={styles["video-body"]}>
        <Image
          className={styles["back-icon"]}
          src={backIcon}
          onClick={() => {
            window.player && window.player.destroy();
            navigate(-1);
          }}
        />
        <div className={styles["video-box"]}>
          <div
            className="play-box"
            style={{ display: playendedStatus ? "none" : "block" }}
            id="meedu-player-container"
          ></div>
          {playendedStatus && (
            <div className={styles["alert-message"]}>
              {isLastpage && (
                <div
                  className={styles["alert-button"]}
                  onClick={() => {
                    navigate(-1);
                  }}
                >
                  恭喜你学完最后一节
                </div>
              )}
              {!isLastpage && (
                <div
                  className={styles["alert-button"]}
                  onClick={() => {
                    setLastSeeValue({});
                    setPlayendedStatus(false);
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
      <div className={styles["chapters-hours-cont"]}>
        {chapters.length === 0 && !hours && <Empty />}
        {chapters.length === 0 && hours && (
          <div className={styles["hours-list-box"]} style={{ marginTop: 10 }}>
            {hours[0].map((item: CourseHourModel) => (
              <div key={item.id} className={styles["hours-it"]}>
                <HourCompenent
                  id={item.id}
                  cid={item.course_id}
                  title={item.title}
                  record={learnHourRecord[item.id]}
                  duration={item.duration}
                  vid={Number(params.hourId)}
                  onSuccess={(cid: number, id: number) => {
                    playVideo(cid, id);
                  }}
                ></HourCompenent>
              </div>
            ))}
          </div>
        )}
        {chapters.length > 0 && hours && (
          <div className={styles["hours-list-box"]}>
            {chapters.map((item: ChapterModel) => (
              <div key={item.id} className={styles["chapter-it"]}>
                <div className={styles["chapter-name"]}>{item.name}</div>
                {hours[item.id]?.map((it: CourseHourModel) => (
                  <div key={it.id} className={styles["hours-it"]}>
                    <HourCompenent
                      id={it.id}
                      cid={item.course_id}
                      title={it.title}
                      record={learnHourRecord[it.id]}
                      duration={it.duration}
                      vid={Number(params.hourId)}
                      onSuccess={(cid: number, id: number) => {
                        playVideo(cid, id);
                      }}
                    ></HourCompenent>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default CoursePlayPage;
